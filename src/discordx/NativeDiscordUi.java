package discordx;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NativeDiscordUi {
    private static final String TAG = "DiscordX";
    private static final Pattern AVATAR = Pattern.compile(
        "https://(?:cdn\\.discordapp\\.com|media\\.discordapp\\.net)/(?:avatars/|guilds/[0-9]+/users/)([1-9][0-9]{16,19})(?:/avatars)?/[a-zA-Z0-9_]+\\.[a-zA-Z0-9]+(?:\\?[^#]*)?");
    final Activity activity;
    final Handler main = new Handler(Looper.getMainLooper());
    final Map<Integer, NativeControls.Action> events = new HashMap<>();
    private final Map<Integer, Node> nodes = new HashMap<>();
    private final Map<Integer, Integer> parents = new HashMap<>();
    private final Set<Integer> settings = new HashSet<>(), profiles = new HashSet<>(), membership = new HashSet<>();
    private final Map<Integer, Insertion> insertions = new HashMap<>();
    private final Map<String, Long> colors = new HashMap<>();
    private final NativeSettings settingsPage;
    private int searchInput = -1, searchParent = -1, searchRow = -1, searchGroup = -1;
    private NativeControls searchResults;
    private String searchTerm = "";
    final PluginPages pluginPages;
    private final Set<Integer> profileArtwork = new HashSet<>(), profileScrolls = new HashSet<>();
    private final Map<Integer,Integer> profileStyles = new HashMap<>();
    private final Map<Integer,Long> profileBackgrounds = new HashMap<>();
    private final EmojiPicker emojiPicker = new EmojiPicker(this);
    private final ChatSurface chat = new ChatSurface(this);
    private int sequence = 0x70000000;
    private boolean submitting, loggedFailure;
    private PhoenixRender render;
    private Object renderer;
    private boolean reportedProfile;
    private boolean reportedMissingScope;
    private final Set<String> profileDiagnostics = new HashSet<>();

    private final int viewStateTag;

    NativeDiscordUi(Activity activity) {
        this.activity = activity;
        viewStateTag = activity.getResources().getIdentifier("wasm_state", "id", activity.getPackageName());
        settingsPage = new NativeSettings(this);
        pluginPages = new PluginPages(this);
    }

    int nextId() { return sequence++; }
    boolean isDark() {
        Long value = colors.get("text");
        if (value != null) return android.graphics.Color.luminance(value.intValue()) > 0.5;
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            != Configuration.UI_MODE_NIGHT_NO;
    }

    String backIcon() {
        for (Node node : nodes.values()) {
            String uri = node.uri;
            if ("image".equals(node.type) && uri.startsWith("res://")
                    && (uri.contains("arrow") || uri.contains("chevron"))
                    && (uri.contains("left") || uri.contains("back"))) return uri;
        }
        return "res://ic_arrow_back_black_24";
    }

    long color(String role, int fallback) {
        return colors.getOrDefault(role, NativeControls.color(fallback));
    }

    Object onBatch(Object owner, Object incoming) {
        if (submitting || activity.isDestroyed()) return incoming;
        try {
            if (render == null) {
                render = new PhoenixRender(activity.getClassLoader());
                Log.i(TAG, "DiscordX attached to decoded Phoenix rendering");
            }
            if (renderer != null && renderer != owner) reset();
            renderer = owner;
            PhoenixRender.Batch batch = render.read(incoming);
            Set<Integer> changed = observe(batch);
            PhoenixRender.Builder output = null;
            for (int root : settings) {
                Node account = find(root, "settings.row.account", 0);
                if (account == null) continue;
                Node navigator = ancestor(root, node -> "navigator".equals(node.type));
                Node list = nodes.get(parents.get(account.id));
                if (navigator == null || list == null || list.children.length == 0) continue;
                if (insertions.containsKey(account.id) && !changed.contains(list.id)) continue;
                learnSettingsColors(account, list, nodes.get(root));
                if (output == null) output = render.addTo(batch);
                final int nav = navigator.id;
                final int page = root;
                output = place(output, account, list, account.id, ui ->
                    settingsEntry(ui, batch, account, (event, payload) -> settingsPage.open(nav, page)), false);
            }
            java.util.ArrayList<PluginUiBridge.ProfileAction> actions = new java.util.ArrayList<>();
            for (PluginUiBridge.ProfileAction action : DiscordX.manager.profiles) if (action.scope.active) actions.add(action);
            Set<Integer> profileLists = new HashSet<>();
            Set<Integer> anchors = new HashSet<>(profiles);
            anchors.addAll(membership);
            if (!actions.isEmpty()) for (int anchor : anchors) {
                Node marker = nodes.get(anchor);
                if (marker == null) continue;
                int member = memberSince(anchor, 0);
                Node scroll = marker.tag("profile-main-scroll") ? marker : find(anchor, "profile-main-scroll", 0);
                if (scroll == null && member >= 0) scroll = ancestor(member, node -> "scroll".equals(node.type));
                if (scroll == null || scroll.children.length == 0) {
                    traceProfile(marker, "waiting for scroll");
                    continue;
                }
                Node list = profileSections(member, scroll);
                if (list == null || list.children.length == 0) {
                    traceProfile(marker, "waiting for content");
                    continue;
                }
                Node profile = profileContainer(scroll.id, marker);
                if (profile == null) {
                    traceProfile(marker, "missing profile scope");
                    if (!reportedMissingScope) Log.i(TAG, "Phoenix profile scroll found outside a supported profile container");
                    reportedMissingScope = true;
                    continue;
                }
                if (!profileLists.add(list.id)) continue;
                String knownUser = profileUser(profile.id, 0, true);
                AvatarMatch avatar = new AvatarMatch();
                if (knownUser == null || knownUser.isEmpty()) findProfileAvatar(profile.id, 0, avatar);
                String user = knownUser != null && !knownUser.isEmpty() ? knownUser : avatar.user();
                if (user == null || user.isEmpty()) {
                    traceProfile(marker, "missing unambiguous profile identity; avatar size " + avatar.size);
                    if (!reportedProfile) Log.i(TAG, "Phoenix profile anchor present; waiting for a verified profile identity");
                    reportedProfile = true;
                    continue;
                }
                Insertion existing = insertions.get(scroll.id);
                if (existing != null && !changed.contains(list.id) && user.equals(existing.subject)) continue;
                if (output == null) output = render.addTo(batch);
                if (existing != null) existing.subject = user;
                final int profileRoot = profile.id;
                if (member < 0) member = memberSince(list.id, 0);
                Node label = nodes.get(member);
                long foreground = label == null ? 0 : label.color;
                int after = list.children[list.children.length - 1];
                int id = member;
                for (int depth = 0; depth < 12 && id > 0 && parents.containsKey(id); depth++, id = parents.get(id)) {
                    if (parents.get(id) == list.id) { after = id; break; }
                }
                output = place(output, scroll, list, after, ui -> {
                    long text = foreground == 0 ? ui.text : foreground;
                    java.util.ArrayList<Integer> rows = new java.util.ArrayList<>();
                    for (PluginUiBridge.ProfileAction action : actions) rows.add(ui.profileLink(action.title,
                        "res://ic_mana_puzzle_piece_plus", text, (event, payload) -> action.scope.guard(() -> action.action.accept(user))));
                    int section = ui.column(0, NativeControls.insets(0, 0));
                    ui.batch.children(section, PhoenixRender.integers(rows));
                    return section;
                }, true);
                insertions.get(scroll.id).subject = user;
            }
            if (DiscordX.manager.hooks.has(discordx.api.Hooks.MENTION_AVATAR)) {
                for (int row = 0; row < batch.count; row++) {
                    if (batch.kind(row) != PhoenixRender.CREATE && batch.kind(row) != PhoenixRender.UPDATE) continue;
                    Node node = nodes.get(batch.id(row));
                    if (node == null || node.spans == null) continue;
                    for (int slot = batch.start(row); slot < batch.start(row) + batch.size(row); slot++) {
                        if (!"spans".equals(batch.key(slot))) continue;
                        Node content = node.access.startsWith("message.content:") ? node :
                            ancestor(node.id, parent -> parent.access.startsWith("message.content:"));
                        if (content == null) continue;
                        Object styled = render.mentionAvatars(node.spans, content.access.substring("message.content:".length()), DiscordX.manager.hooks);
                        if (styled == node.spans) continue;
                        if (output == null) output = render.addTo(batch);
                        output.update(node.id, "spans", styled);
                    }
                }
            }
            if (searchResults != null && changed.contains(searchParent)) {
                if (output == null) output = render.addTo(batch);
                attachSearchResults(output);
            }
            output = profileAppearance(batch, output);
            output = emojiPicker.render(batch, output, changed);
            output = chat.render(batch, output, changed);
            return output == null ? incoming : output.build();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failure("Could not integrate the Phoenix render batch", error);
            return incoming;
        }
    }

    private static boolean decorativeMedia(Node node) {
        String uri = node.uri;
        return uri.contains("/banners/") || uri.contains("/avatar-decoration-presets/")
            || uri.contains("/profile-effects/") || uri.contains("/profile_effects/")
            || uri.contains("/nameplates/") || node.tag("profile-avatar-decoration") || node.tag("profile-nameplate") || node.tag("profile-effect") || node.tag("profile-banner-image");
    }

    private static final int SURFACE = 1, BANNER = 2, TITLE = 4, ART = 8, DEFAULT_BASE = 16, BODY_TEXT = 32, SHEET = 64, BUTTON = 128, TAB = 256, ACTIVE_TAB = 512;

    private PhoenixRender.Builder profileAppearance(PhoenixRender.Batch source, PhoenixRender.Builder output)
            throws ReflectiveOperationException {
        HookRegistry hooks = DiscordX.manager.hooks;
        boolean suppress = hooks.has(discordx.api.Hooks.PROFILE_APPEARANCE);
        boolean recolor = hooks.has(discordx.api.Hooks.DEFAULT_PROFILE_COLORS);
        if ((!suppress && !recolor || profileScrolls.isEmpty()) && profileStyles.isEmpty()) return output;
        Map<Integer,Integer> next = new HashMap<>();
        Map<Integer,Long> backgrounds = new HashMap<>();
        for (int id : profileScrolls) {
            Node scroll = nodes.get(id);
            if (scroll == null) continue;
            Node sheet = ancestor(id, node -> "sheet".equals(node.type) || node.tag("profile-page"));
            if (sheet == null) continue;
            String identity = profileUser(id, 0, true);
            if (identity == null || identity.isEmpty()) {
                AvatarMatch match = new AvatarMatch();
                findProfileAvatar(id, 0, match);
                identity = match.user();
            }
            final String user = identity;
            Boolean selected = ProfileIdentity.customPalette(user, null);
            boolean paintedBackdrop = hasProfileGradient(sheet.id, scroll.id, 0);
            boolean custom = Boolean.TRUE.equals(selected) || paintedBackdrop;
            // Only the sheet's own stock base is eligible. An explicit gradient or non-default fill stays intact.
            if (recolor && Boolean.FALSE.equals(selected) && !paintedBackdrop && stockProfileBase(sheet.background)) {
                int[] palette = hooks.fold(discordx.api.Hooks.DEFAULT_PROFILE_COLORS, (int[])null,
                    (provider, old) -> old == null ? provider.get() : old);
                if (palette != null && palette.length == 2) {
                    next.put(sheet.id, DEFAULT_BASE);
                    backgrounds.put(sheet.id, 0xff000000L | (palette[0] & 0xffffffL));
                }
            }
            if (!suppress || !hooks.fold(discordx.api.Hooks.PROFILE_APPEARANCE, false,
                    (filter, previous) -> previous || filter.test(user))) continue;
            // Keep Phoenix's original default header, text and controls when no theme was selected.
            boolean dark = isDark();
            long base = dark ? 0xff000000L : 0xffffffffL;
            if (paintedBackdrop) {
                next.put(sheet.id, SURFACE | SHEET);
                backgrounds.put(sheet.id, base);
                profileControls(scroll, next, backgrounds, dark, 0);
            }
            // Phoenix draws its solid banner as the first stack in the scroll content.
            Node content = scroll;
            for (int hop = 0; hop < 4 && content != null && content.children.length == 1; hop++)
                content = nodes.get(content.children[0]);
            if (content != null && content.children.length > 1) {
                Node header = nodes.get(content.children[0]);
                if (paintedBackdrop && header != null && "stack".equals(header.type) && header.height >= 60) {
                    next.put(header.id, BANNER);
                    backgrounds.put(header.id, base);
                }
            }
            for (int part : profileArtwork) {
                Node n = nodes.get(part);
                if (n == null || ancestor(part, parent -> parent.id == id) == null) continue;
                int flags = next.getOrDefault(part, 0);
                if (decorativeMedia(n) || avatarOverlay(n)) flags |= ART;
                if (custom && n.backgroundGradient != null) {
                    flags |= SURFACE;
                    backgrounds.put(part, base);
                }
                if ("text".equals(n.type) && (n.textGradient != null || paintedBackdrop && n.fontSize >= 20)) flags |= TITLE;

                if (flags != 0) next.put(part, flags);
            }
        }
        Set<Integer> affected = new HashSet<>(next.keySet());
        affected.addAll(profileStyles.keySet());
        Set<Integer> touched = new HashSet<>();
        for (int row = 0; row < source.count; row++)
            if (source.kind(row) == PhoenixRender.CREATE || source.kind(row) == PhoenixRender.UPDATE) touched.add(source.id(row));
        for (int id : affected) {
            Node n = nodes.get(id);
            if (n == null) continue;
            int old = profileStyles.getOrDefault(id, 0), flags = next.getOrDefault(id, 0);
            if (old == flags && !touched.contains(id) && java.util.Objects.equals(profileBackgrounds.get(id), backgrounds.get(id))) continue;
            if (output == null) output = render.addTo(source);
            int both = old | flags;
            if ((both & (SURFACE | BANNER | DEFAULT_BASE)) != 0) {
                output.update(id, "background", backgrounds.getOrDefault(id, n.background));
                if (n.backgroundGradient != null) output.update(id, "backgroundGradient", flags == 0 ? n.backgroundGradient : null);
            }
            if ((both & BUTTON) != 0 && n.worklet != null) {
                Object worklet = n.worklet;
                if ((flags & BUTTON) != 0) {
                    long fill = backgrounds.get(id);
                    worklet = render.buttonColors(worklet, fill, fill == 0xff5865f2L ? 0xff4752c4L : (isDark() ? 0xff34343aL : 0xffdedee3L));
                }
                output.update(id, "worklet", worklet);
            }
            if ((both & SHEET) != 0 && n.scrimColor != 0)
                output.update(id, "scrimColor", (flags & SHEET) != 0 ? 0x99000000L : n.scrimColor);
            if ((both & BODY_TEXT) != 0)
                output.update(id, "color", (flags & BODY_TEXT) != 0 ? (isDark() ? 0xffededf0L : 0xff242429L) : n.color);
            if ((both & TAB) != 0) {
                boolean themed = (flags & TAB) != 0;
                boolean active = (flags & ACTIVE_TAB) != 0;
                long ink = isDark() ? (active ? 0xffffffffL : 0xffb5bac1L) : (active ? 0xff242429L : 0xff535862L);
                output.update(id, "worklet", themed ? null : n.worklet, "color", themed ? ink : n.color);
            }
            if ((both & ART) != 0) output.update(id, "opacity", (flags & ART) != 0 ? 0.0 : n.opacity);
            if ((both & TITLE) != 0) {
                boolean plain = (flags & TITLE) != 0;
                output.update(id, "color", plain ? NativeControls.color(isDark() ? 0xfff2f3f5 : 0xff242529) : n.color);
                if (n.textGradient != null) output.update(id, "textGradient", plain ? null : n.textGradient);
                if (n.spans != null) output.update(id, "spans", plain ? render.plainSpans(n.spans) : n.spans);
            }
        }
        profileStyles.clear();
        profileStyles.putAll(next);
        profileBackgrounds.clear();
        profileBackgrounds.putAll(backgrounds);
        return output;
    }

    private boolean avatarOverlay(Node layer) {
        if (!"image".equals(layer.type) || !layer.absolute || layer.width <= 0 || layer.height != layer.width) return false;
        Node stack = nodes.get(parents.get(layer.id));
        if (stack == null || !"stack".equals(stack.type) || layer.width < stack.width || stack.width < 48) return false;
        for (int child : stack.children) {
            Node avatar = nodes.get(child);
            if (avatar != null && avatar != layer && avatar.profileId(false) != null && avatar.width == stack.width) return true;
        }
        return false;
    }

    private void profileControls(Node n, Map<Integer,Integer> styles, Map<Integer,Long> fills, boolean dark, int depth) {
        if (depth > 32) return;
        if ("view".equals(n.type) || "stack".equals(n.type)) {
            if (n.background != 0) {
                boolean button = n.pressFeedback && n.worklet != null;
                boolean primary = button && (n.background >>> 24) == 255 && n.flex;
                long surface = dark ? 0xff222226L : 0xfff2f2f5L;
                if (n.absolute && n.height == 2 && n.worklet != null && tabStrip(nodes.get(parents.get(n.id)))) {
                    surface = dark ? 0xffededf0L : 0xff242429L;
                }
                fills.put(n.id, primary ? 0xff5865f2L : surface);
                styles.put(n.id, button ? SURFACE | BUTTON : SURFACE);
            }
        } else if ("text".equals(n.type) && n.worklet != null && tabButton(n) != null) {
            Node button = tabButton(n);
            styles.put(n.id, Boolean.TRUE.equals(button.selected) ? TAB | ACTIVE_TAB : TAB);
        } else if ("text".equals(n.type) && (n.color >>> 24) != 0) {
            int color = (int)n.color;
            int r = android.graphics.Color.red(color), g = android.graphics.Color.green(color), b = android.graphics.Color.blue(color);
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 32)
                styles.put(n.id, BODY_TEXT);
        }
        for (int child : n.children) {
            Node node = nodes.get(child);
            if (node != null) profileControls(node, styles, fills, dark, depth + 1);
        }
    }

    private Node tabButton(Node label) {
        Node parent = nodes.get(parents.get(label.id));
        Node button = parent == null ? null : nodes.get(parents.get(parent.id));
        return button != null && button.selected != null && tabStrip(nodes.get(parents.get(button.id))) ? button : null;
    }

    private boolean tabStrip(Node row) {
        Node scroller = row == null ? null : nodes.get(parents.get(row.id));
        return scroller != null && "hscroll".equals(scroller.type) && !scroller.tag("profile-tab-pages");
    }

    private static boolean stockProfileBase(long argb) {
        return argb == 0xff000000L || argb == 0xffffffffL;
    }

    private boolean hasProfileGradient(int id, int scroll, int depth) {
        if (depth > 7) return false;
        Node node = nodes.get(id);
        if (node == null) return false;
        if (node.backgroundGradient != null || "gradient".equals(node.type) || node.gradient != null) return true;
        // Only the backdrop counts. Gradients inside a badge or avatar are independent cosmetics.
        if (id == scroll) return false;
        for (int child : node.children) if (hasProfileGradient(child, scroll, depth + 1)) return true;
        return false;
    }

    private void attachSearchResults(PhoenixRender.Builder batch) {
        Node parent = nodes.get(searchParent);
        if (parent == null) return;
        for (int i = 0; i < parent.children.length; i++) if (parent.children[i] == searchRow) {
            batch.children(parent.id, inserting(parent.children, i + 1, searchGroup));
            return;
        }
    }

    private void clearSearch(PhoenixRender.Builder batch) throws ReflectiveOperationException {
        if (searchResults != null) {
            Node parent = nodes.get(searchParent);
            if (parent != null) batch.children(parent.id, parent.children);
            discard(batch, searchResults);
        }
        searchResults = null;
        searchInput = searchParent = searchRow = searchGroup = -1;
        searchTerm = "";
    }

    private void searchSettings(Node input, String value) throws ReflectiveOperationException {
        String term = value.trim().toLowerCase(java.util.Locale.ROOT);
        PhoenixRender.Builder batch = builder();
        clearSearch(batch);
        Node row = input;
        Node parent = nodes.get(parents.get(row.id));
        while (parent != null && ("row".equals(parent.direction) || !"view".equals(parent.type))) {
            row = parent;
            parent = nodes.get(parents.get(row.id));
        }
        if (!term.isEmpty() && parent != null) {
            searchInput = input.id;
            searchTerm = term;
            NativeControls ui = new NativeControls(this, batch);
            java.util.ArrayList<Integer> matches = new java.util.ArrayList<>();
            for (PluginManager.Entry entry : DiscordX.manager.entries.values()) {
                if (entry.pack.name.toLowerCase(java.util.Locale.ROOT).contains(term)) {
                    matches.add(ui.row(entry.pack.name, "DiscordX", "", ui.card,
                        (event, payload) -> settingsPage.openDetails(entry.pack.id)));
                }
            }
            for (PluginUiBridge.Setting setting : DiscordX.manager.settings) {
                if (setting.scope.active && setting.title.toLowerCase(java.util.Locale.ROOT).contains(term))
                    matches.add(ui.row(setting.title, setting.scope.info().name, "", ui.card,
                        (event, payload) -> setting.scope.guard(() -> setting.scope.ui().open(setting.title, setting.page))));
            }
            if (!matches.isEmpty()) {
                searchResults = ui;
                searchParent = parent.id;
                searchRow = row.id;
                searchGroup = ui.column(0, NativeControls.insets(0, 0));
                batch.children(searchGroup, PhoenixRender.integers(matches));
                attachSearchResults(batch);
                events.putAll(ui.actions);
            }
        }
        submit(batch);
    }

    private interface BuildInsertion { int create(NativeControls ui) throws ReflectiveOperationException; }

    private PhoenixRender.Builder place(PhoenixRender.Builder batch, Node anchor, Node parent, int neighbor,
            BuildInsertion build, boolean after) throws ReflectiveOperationException {
        Insertion existing = insertions.get(anchor.id);
        if (existing != null) discard(batch, existing.ui);
        NativeControls ui = new NativeControls(this, batch);
        int row = build.create(ui);
        int[] children = parent.children;
        for (int i = 0; i < children.length; i++) if (children[i] == neighbor) {
            batch.children(parent.id, inserting(children, i + (after ? 1 : 0), row));
            insertions.put(anchor.id, new Insertion(ui, parent.id, after));
            events.putAll(ui.actions);
            Log.i(TAG, after ? "ReviewDB profile row in Phoenix batch" : "DiscordX Settings row in Phoenix batch");
            if (after) main.post(() -> checkProfileRow(row, parent.id));
            break;
        }
        return batch;
    }

    private Set<Integer> observe(PhoenixRender.Batch batch) throws IllegalAccessException {
        Set<Integer> changed = new HashSet<>();
        for (int i = 0; i < batch.count; i++) {
            int id = batch.id(i);
            switch (batch.kind(i)) {
                case PhoenixRender.CREATE:
                    nodes.put(id, new Node(id, (String) batch.types[i]));
                    // A create also supplies an initial set of properties.
                case PhoenixRender.UPDATE:
                    Node node = nodes.get(id);
                    if (node == null) break;
                    for (int p = batch.start(i); p < batch.start(i) + batch.size(i); p++) {
                        Object value = batch.objects[p];
                        String key = batch.key(p);
                        switch (key) {
                            case "accessibilitySelected": node.selected = batch.tags[p] == 0 ? null : batch.tags[p] == 2; break;
                            case "accessibilityLabel": node.label = string(value); break;
                            case "accessibilityId": node.access = string(value); break;
                            case "direction": node.direction = string(value); break;
                            case "nativeID": node.nativeId = string(value); break;
                            case "backgroundGradient": node.backgroundGradient = value; break;
                            case "textGradient": node.textGradient = value; break;
                            case "fontSize": node.fontSize = dimension(batch, p); break;
                            case "gradient": node.gradient = value; break;
                            case "spans": node.spans = value; break;
                            case "text":
                                node.text = string(value);
                                if (node.text.isEmpty() && render.containsText(value, "Member Since", 4)) node.text = "Member Since";
                                break;
                            case "opacity": node.opacity = batch.tags[p] == 5 ? Double.longBitsToDouble(batch.numbers[p]) : dimension(batch, p); break;
                            case "uri": node.uri = string(value); break;
                            case "scrimColor": node.scrimColor = rgba(batch, p); break;
                            case "absolute": node.absolute = batch.tags[p] == 2 || value != null; break;
                            case "worklet": node.worklet = value; break;
                            case "pressFeedback": node.pressFeedback = batch.tags[p] != 0; break;
                            case "flex": node.flex = dimension(batch, p) > 0; break;
                            case "background": node.background = rgba(batch, p); break;
                            case "color": node.color = rgba(batch, p); break;
                            case "width": node.width = dimension(batch, p); break;
                            case "height": node.height = dimension(batch, p); break;
                            case "radius": node.radius = dimension(batch, p); break;
                            case "index": node.index = (int) batch.numbers[p]; break;
                        }
                        if ("image".equals(node.type) && node.uri.isEmpty() && value instanceof String
                                && ((String) value).startsWith("https://") && AVATAR.matcher((String) value).matches()) node.uri = (String) value;
                    }
                    if (node.tag("profile-scroll") || node.tag("profile-main-scroll")) profileScrolls.add(id);
                    else profileScrolls.remove(id);
                    if (node.backgroundGradient != null || "image".equals(node.type) && node.absolute || decorativeMedia(node) || node.textGradient != null ||
                            "text".equals(node.type) && node.fontSize >= 20) profileArtwork.add(id);
                    else profileArtwork.remove(id);
                    chat.observe(node);
                    emojiPicker.observe(node);
                    if (node.tag("settings-phone") || node.tag("settings-tablet")) settings.add(id);
                    if (node.tag("profile-sheet") || node.tag("profile-main-scroll")) profiles.add(id);
                    if (node.tag("member-since") || node.tag("member-since-discord") || "Member Since".equals(node.text)) membership.add(id);
                    break;
                case PhoenixRender.CHILDREN:
                    Node parent = nodes.get(id);
                    if (parent == null) break;
                    for (int child : parent.children) parents.remove(child, id);
                    parent.children = batch.children(i);
                    for (int child : parent.children) parents.put(child, id);
                    changed.add(id);
                    break;
                case PhoenixRender.DELETE:
                    for (int dead : batch.children(i)) {
                        if (dead == searchInput || dead == searchParent || dead == searchRow) {
                            int input = searchInput;
                            main.post(() -> {
                                if (input != searchInput || nodes.containsKey(dead)) return;
                                try { PhoenixRender.Builder cleanup = builder(); clearSearch(cleanup); submit(cleanup); }
                                catch (ReflectiveOperationException error) { failure("Cannot close settings search", error); }
                            });
                        }
                        profileArtwork.remove(dead); profileScrolls.remove(dead); profileStyles.remove(dead);
                        emojiPicker.removed(dead);
                        chat.removed(dead);
                        Node removed = nodes.remove(dead);
                        if (removed != null) for (int child : removed.children) parents.remove(child, dead);
                        parents.remove(dead);
                        settings.remove(dead);
                        profiles.remove(dead);
                        membership.remove(dead);
                        Insertion entry = insertions.remove(dead);
                        if (entry != null) main.post(() -> delete(entry.ui));
                        settingsPage.hostRemoved(dead);

                    }
                    break;
            }
        }
        return changed;
    }

    private void learnSettingsColors(Node row, Node group, Node screen) {
        if (screen != null && screen.background != 0) colors.put("background", screen.background);
        long background = row.background != 0 ? row.background : group.background;
        if (background != 0) colors.put("card", background);
        Long label = firstTextColor(row, 0);
        if (label != null) colors.put("text", label);
    }

    private Long firstTextColor(Node node, int depth) {
        if (depth > 7) return null;
        if ("text".equals(node.type) && node.color != 0) return node.color;
        for (int id : node.children) {
            Node child = nodes.get(id);
            if (child == null) continue;
            Long result = firstTextColor(child, depth + 1);
            if (result != null) return result;
        }
        return null;
    }

    private String rightmostIcon(Node node, int depth) {
        if (depth > 7) return "";
        if ("image".equals(node.type) && node.uri.startsWith("res://")
                && node.uri.contains("chevron")) return node.uri;
        for (int i = node.children.length - 1; i >= 0; i--) {
            Node child = nodes.get(node.children[i]);
            if (child == null) continue;
            String icon = rightmostIcon(child, depth + 1);
            if (!icon.isEmpty()) return icon;
        }
        return "";
    }

    private int settingsEntry(NativeControls ui, PhoenixRender.Batch batch, Node account, NativeControls.Action open)
            throws ReflectiveOperationException {
        Set<Integer> created = new HashSet<>();
        for (int row = 0; row < batch.count; row++) if (batch.kind(row) == PhoenixRender.CREATE) created.add(batch.id(row));
        Node title = firstOfType(account, "text", 0);
        Node icon = firstOfType(account, "image", 0);
        if (title == null || !isStyleTemplate(account, created, 0)) return ui.settingsRow(rightmostIcon(account, 0), open);
        int entry = mirrorSettings(ui, batch, account, account.id, title.id, icon == null ? -1 : icon.id);
        ui.actions.put(entry, open);
        return entry;
    }

    private boolean isStyleTemplate(Node node, Set<Integer> created, int depth) {
        if (node == null || depth > 7 || !created.contains(node.id)) return false;
        if (!("view".equals(node.type) || "text".equals(node.type) || "image".equals(node.type) || "stack".equals(node.type))) {
            return false;
        }
        for (int id : node.children) if (!isStyleTemplate(nodes.get(id), created, depth + 1)) return false;
        return true;
    }

    private Node firstOfType(Node node, String type, int depth) {
        if (node == null || depth > 7) return null;
        if (type.equals(node.type)) return node;
        for (int id : node.children) {
            Node result = firstOfType(nodes.get(id), type, depth + 1);
            if (result != null) return result;
        }
        return null;
    }

    private int mirrorSettings(NativeControls ui, PhoenixRender.Batch batch, Node source, int root, int title, int icon)
            throws ReflectiveOperationException {
        int id = nextId();
        ui.nodes.add(id);
        if (source.id == root) ui.batch.mirror(batch, source.id, id, source.type,
            "on", NativeControls.PRESS, "role", "button", "accessibilityId", "settings.row.discordx",
            "accessibilityLabel", "DiscordX Plugins");
        else if (source.id == title) ui.batch.mirror(batch, source.id, id, source.type, "text", "DiscordX Plugins");
        else if (source.id == icon) ui.batch.mirror(batch, source.id, id, source.type, "uri", "res://ic_mana_puzzle_piece_plus");
        else ui.batch.mirror(batch, source.id, id, source.type);
        int[] children = new int[source.children.length];
        for (int i = 0; i < children.length; i++) {
            children[i] = mirrorSettings(ui, batch, nodes.get(source.children[i]), root, title, icon);
        }
        if (children.length > 0) ui.batch.children(id, children);
        return id;
    }

    private Node find(int root, String tag, int depth) {
        Node node = nodes.get(root);
        if (node == null || depth > 13) return null;
        if (node.tag(tag)) return node;
        for (int id : node.children) {
            Node hit = find(id, tag, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private int memberSince(int id, int depth) {
        Node node = nodes.get(id);
        if (node == null || depth > 10) return -1;
        if (node.tag("member-since") || node.tag("member-since-discord") || "Member Since".equals(node.text)) return id;
        for (int child : node.children) {
            int match = memberSince(child, depth + 1);
            if (match >= 0) return match;
        }
        return -1;
    }

    private Node below(int id, int parent) {
        for (int depth = 0; depth < 12 && parents.containsKey(id); depth++) {
            int next = parents.get(id);
            if (next == parent) return nodes.get(id);
            id = next;
        }
        return null;
    }

    private Node profileSections(int member, Node scroll) {
        if (member < 0) return nodes.get(scroll.children[0]);
        int id = member;
        for (int depth = 0; depth < 8 && parents.containsKey(id); depth++) {
            id = parents.get(id);
            if (id == scroll.id) break;
            Node node = nodes.get(id);
            if (node != null && node.children.length >= 2
                    && (containsLabel(id, "Connections", 0) || containsLabel(id, "Roles", 0))) return node;
        }
        return below(member, scroll.id);
    }

    private void checkProfileRow(int row, int parentId) {
        try {
            android.view.View view = render.view(renderer, row);
            android.view.View parent = render.view(renderer, parentId);
            if (view == null) {
                Log.w(TAG, "Phoenix did not create the injected Reviews view");
                return;
            }
            view.postOnAnimation(() -> Log.i(TAG, "Phoenix Reviews view " + view.getWidth() + "x" + view.getHeight()
                + ", attached=" + view.isAttachedToWindow() + ", parent=" + (view.getParent() == parent)));
        } catch (IllegalAccessException error) {
            failure("Could not inspect the Phoenix Reviews view", error);
        }
    }

    private Node profileContainer(int scroll, Node marker) {
        if (marker.tag("profile-sheet")) return marker;
        Node named = ancestor(scroll, node -> node.tag("profile-sheet") || node.tag("profile-page"));
        if (named != null && profileUser(named.id, 0, true) != null) return named;
        Node overlay = ancestor(scroll, node -> "sheet".equals(node.type) || "modal".equals(node.type)
            || "popover".equals(node.type));
        if (overlay != null) return overlay;
        return ancestor(scroll, node -> find(node.id, "profile-tabs", 0) != null
            || containsLabel(node.id, "Main", 0) && containsLabel(node.id, "Activity", 0));
    }

    boolean containsLabel(int id, String label, int depth) {
        Node node = nodes.get(id);
        if (node == null || depth > 7) return false;
        if (label.equals(node.text)) return true;
        for (int child : node.children) if (containsLabel(child, label, depth + 1)) return true;
        return false;
    }

    private void traceProfile(Node marker, String reason) {
        if (!membership.contains(marker.id) || profileDiagnostics.size() >= 6) return;
        StringBuilder types = new StringBuilder(marker.type);
        int id = marker.id;
        for (int depth = 0; depth < 10 && parents.containsKey(id); depth++) {
            id = parents.get(id);
            Node node = nodes.get(id);
            if (node != null) types.append('/').append(node.type);
        }
        String message = "Phoenix profile render " + reason + " (" + types + ")";
        if (profileDiagnostics.add(message)) Log.i(TAG, message);
    }

    private String profileUser(int root, int depth, boolean preferred) {
        Node node = nodes.get(root);
        if (node == null || depth > 10) return null;
        String result = node.profileId(preferred);
        if (result == null && preferred && (node.access.startsWith("profile-avatar-") || node.nativeId.startsWith("profile-avatar-"))) {
            result = profileUser(root, 0, false);
        }
        if (result != null) return result;
        for (int child : node.children) {
            String candidate = profileUser(child, depth + 1, preferred);
            if (candidate == null) continue;
            if (result != null && !result.equals(candidate)) return "";
            result = candidate;
        }
        return result;
    }

    private void findProfileAvatar(int id, int depth, AvatarMatch best) {
        Node node = nodes.get(id);
        if (node == null || depth > 12) return;
        String user = node.profileId(false);
        if (user != null) {
            int size = node.avatarSize();
            Node parent = nodes.get(parents.get(id));
            if (size == 0 && parent != null) size = parent.avatarSize();
            best.offer(user, size);
        }
        String name = node.text;
        if ("text".equals(node.type) && name.length() <= 48) {
            int space = name.indexOf(' ');
            if (space >= 0) name = name.substring(0, space);
            String verified = ProfileIdentity.forUsername(name);
            if (verified != null) best.offer(verified, 12);
        }
        for (int child : node.children) findProfileAvatar(child, depth + 1, best);
    }

    private Node ancestor(int id, java.util.function.Predicate<Node> test) {
        for (int i = 0; i < 14 && parents.containsKey(id); i++) {
            id = parents.get(id);
            Node node = nodes.get(id);
            if (node != null && test.test(node)) return node;
        }
        return null;
    }

    Iterable<Node> visibleNodes() { return nodes.values(); }
    boolean insertEmoji(String token) throws ReflectiveOperationException { return chat.insertEmoji(token); }
    Integer parentId(int id) { return parents.get(id); }
    void changeDraft(android.view.View input, String text) throws ReflectiveOperationException {
        render.changeDraft(renderer, viewState(input), text);
    }
    android.view.View nativeView(int id) throws IllegalAccessException { return render.view(renderer, id); }
    PhoenixRender.Builder copyBatch(PhoenixRender.Batch batch) throws ReflectiveOperationException { return render.addTo(batch); }
    void redrawChat(String id) {
        chat.invalidate(id);
        main.post(() -> {
            if (renderer == null || activity.isDestroyed()) return;
            try {
                PhoenixRender.Builder draw = chat.render(null, null, java.util.Collections.emptySet());
                if (draw != null) submit(draw);
            } catch (ReflectiveOperationException error) { failure("Cannot redraw chat", error); }
        });
    }
    Node node(int id) { return nodes.get(id); }
    PhoenixRender.Builder builder() throws ReflectiveOperationException { return render.addTo(null); }
    String inputText(Object payload) throws IllegalAccessException { return render.inputText(payload); }

    void submit(PhoenixRender.Builder batch) throws ReflectiveOperationException {
        if (renderer == null || activity.isDestroyed()) return;
        submitting = true;
        try {
            render.submit(renderer, batch);
        } finally {
            submitting = false;
        }
    }

    void delete(NativeControls ui) {
        try {
            PhoenixRender.Builder batch = builder();
            discard(batch, ui);
            submit(batch);
        } catch (ReflectiveOperationException | RuntimeException error) {
            failure("Could not remove a Phoenix render scope", error);
        }
    }

    void discard(PhoenixRender.Builder batch, NativeControls ui) {
        events.keySet().removeAll(ui.actions.keySet());
        batch.delete(ui.nodeIds());
    }

    boolean event(int id, String event, Object payload, Object completion) {
        Node screen = nodes.get(id);
        try {
            if (screen != null && screen.tag("user_settings_search_input") && "changeText".equals(event)) {
                String query = inputText(payload);
                if (query != null) main.post(() -> {
                    try { if (nodes.get(id) == screen) searchSettings(screen, query); }
                    catch (ReflectiveOperationException error) { failure("Cannot update settings search", error); }
                });
            }
            if (emojiPicker.event(screen, event) || chat.event(screen, event, payload)) {
                try { render.complete(completion); }
                catch (ReflectiveOperationException error) { failure("Cannot finish consumed composer event", error); }
                return true;
            }
        } catch (ReflectiveOperationException error) { failure("Cannot handle chat event", error); }
        if (screen != null && ("navigate".equals(event) || "appeared".equals(event))) {
            chat.navigated();
            if (!DiscordX.manager.localReplies.isEmpty()) redrawChat(null);
            String tag = screen.access.isEmpty() ? screen.nativeId : screen.access;
            if (!tag.isEmpty()) DiscordX.manager.hooks.each(discordx.api.Hooks.NAVIGATION,
                callback -> callback.accept(new discordx.api.Hooks.Navigation(event, tag)));
        }
        NativeControls.Action action = events.get(id);
        if (action == null && settingsPage.intercepts(id, event)) action = (name, data) -> settingsPage.close();
        if (action == null && (id < 0x70000000 || id >= sequence)) return false;
        final NativeControls.Action task = action;
        main.post(() -> {
            try {
                if (task != null && !activity.isDestroyed()) task.run(event, payload);
            } catch (Exception error) {
                failure("Phoenix plugin action failed", error);
            } finally {
                try {
                    if (render != null) render.complete(completion);
                } catch (ReflectiveOperationException error) {
                    failure("Phoenix plugin action did not complete", error);
                }
            }
        });
        return true;
    }

    boolean back() { return settingsPage.back(); }

    boolean settingsShortcut() {
        if (settingsPage.back()) return true;
        for (int overview : settings) {
            Node nav = ancestor(overview, node -> "navigator".equals(node.type));
            if (nav == null || nav.children.length == 0) continue;
            int index = nav.index < 0 ? nav.children.length - 1 : nav.index;
            if (index >= nav.children.length) continue;
            int selected = nav.children[index];
            if (overview != selected && ancestor(overview, node -> node.id == selected) == null) continue;
            main.post(() -> {
                try {
                    settingsPage.open(nav.id, overview);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    failure("Could not open the Phoenix settings page", error);
                }
            });
            return true;
        }
        if (render != null && renderer != null) {
            main.post(() -> {
                try { settingsPage.openModal(); }
                catch (ReflectiveOperationException error) { failure("Could not open plugin manager", error); }
            });
            return true;
        }
        return false;
    }

    private Object viewState(android.view.View view) { return viewStateTag == 0 ? null : view.getTag(viewStateTag); }

    boolean overlayEvent(android.view.View view, String event) {
        Object state = viewState(view);
        if (state == null || !("dismiss".equals(event) || "exitEnd".equals(event))) return false;
        try {
            int id = state.getClass().getField("a").getInt(state);
            return settingsPage.overlayEvent(id) || pluginPages.overlayEvent(id);
        } catch (ReflectiveOperationException error) {
            failure("Could not identify the Phoenix modal event", error);
            return false;
        }
    }
    void reset() {
        searchResults = null;
        searchInput = searchParent = searchRow = searchGroup = -1;
        searchTerm = "";
        profileArtwork.clear(); profileScrolls.clear(); profileStyles.clear(); profileBackgrounds.clear();
        emojiPicker.reset();
        chat.reset();
        pluginPages.reset();
        events.clear();
        nodes.clear();
        parents.clear();
        settings.clear();
        profiles.clear();
        membership.clear();
        insertions.clear();
        settingsPage.reset();
    }

    void pluginsChanged() {
        main.post(() -> {
            if (activity.isDestroyed()) return;
            try {
                if (renderer != null && render != null) {
                    PhoenixRender.Builder batch = builder();
                    java.util.Iterator<Map.Entry<Integer, Insertion>> iterator = insertions.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Insertion insertion = iterator.next().getValue();
                        if (!insertion.profile) continue;
                        Node parent = nodes.get(insertion.parent);
                        if (parent != null) batch.children(parent.id, parent.children);
                        discard(batch, insertion.ui);
                        iterator.remove();
                    }
                    for (Node node : nodes.values()) if (node.spans != null) batch.update(node.id, "spans", node.spans);
                    chat.invalidate(null);
                    Object updated = onBatch(renderer, batch.build());
                    render.submitBatch(renderer, updated);
                }
                Node input = nodes.get(searchInput);
                if (input != null) searchSettings(input, searchTerm);
                settingsPage.refresh();
            } catch (ReflectiveOperationException | RuntimeException error) { failure("Could not refresh plugin contributions", error); }
        });
    }

    void failure(String message, Throwable error) {
        if (!loggedFailure) Log.w(TAG, message, error);
        loggedFailure = true;
    }

    private void copy(String user) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Discord profile link", "https://discord.com/users/" + user));
    }

    private static String string(Object value) { return value instanceof String ? (String) value : ""; }

    private static long rgba(PhoenixRender.Batch batch, int p) {
        return batch.tags[p] == 3 || batch.tags[p] == 4 ? batch.numbers[p] & 0xffffffffL : 0;
    }

    private static int dimension(PhoenixRender.Batch batch, int slot) {
        if (batch.tags[slot] == 5) return (int) Math.round(Double.longBitsToDouble(batch.numbers[slot]));
        if (batch.tags[slot] == 3 || batch.tags[slot] == 4) return (int) batch.numbers[slot];
        return 0;
    }

    static int[] inserting(int[] original, int index, int id) {
        int[] next = Arrays.copyOf(original, original.length + 1);
        System.arraycopy(original, index, next, index + 1, original.length - index);
        next[index] = id;
        return next;
    }

    private static final class Insertion {
        final NativeControls ui;
        String subject;
        final int parent;
        final boolean profile;
        Insertion(NativeControls ui, int parent, boolean profile) { this.ui = ui; this.parent = parent; this.profile = profile; }
    }

    private static final class AvatarMatch {
        int size = -1;
        String id;
        boolean ambiguous;

        void offer(String user, int candidate) {
            if (candidate < size) return;
            if (candidate > size) {
                size = candidate;
                id = user;
                ambiguous = false;
            } else if (!user.equals(id)) ambiguous = true;
        }

        String user() { return ambiguous ? null : id; }
    }

    static final class Node {
        final int id;
        final String type;
        double opacity = 1.0;
        Object spans, backgroundGradient, textGradient, gradient, worklet;
        int fontSize;
        String direction = "", access = "", nativeId = "", text = "", uri = "", label = "";
        long color, background, scrimColor;
        boolean absolute, pressFeedback, flex;
        Boolean selected;
        int width, height, radius;
        int index = -1;
        int[] children = {};

        Node(int id, String type) { this.id = id; this.type = type; }
        boolean tag(String name) { return name.equals(access) || name.equals(nativeId); }

        int avatarSize() {
            if (width >= 48 && width <= 144 && Math.abs(width - height) <= 2) return width;
            return radius >= 24 && radius <= 72 ? radius * 2 : 0;
        }

        String profileId(boolean preferred) {
            if (!preferred) {
                Matcher avatar = AVATAR.matcher(uri);
                return avatar.matches() ? avatar.group(1) : null;
            }
            for (String name : new String[] {access, nativeId}) {
                if (!name.startsWith("profile-avatar-")) continue;
                String id = name.substring("profile-avatar-".length());
                if (Ids.snowflake(id)) return id;
                Matcher avatar = AVATAR.matcher(uri);
                if (avatar.matches()) return avatar.group(1);
            }
            return tag("profile-user-tag") ? ProfileIdentity.forUsername(text) : null;
        }
    }
}
