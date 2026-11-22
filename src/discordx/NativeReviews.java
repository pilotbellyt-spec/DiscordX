package discordx;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NativeReviews {
    private static final ExecutorService REQUESTS = Executors.newFixedThreadPool(2);
    private static String token;
    private static ReviewDB.Account account;
    private final NativeDiscordUi owner;
    private final Map<String, CachedPage> recent = new LinkedHashMap<>();
    private Session open;

    NativeReviews(NativeDiscordUi owner) { this.owner = owner; }

    void open(int profile, String user) throws ReflectiveOperationException {
        if (open != null || owner.node(profile) == null || !MessageLogger.snowflake(user)) return;
        PhoenixRender.Builder batch = owner.builder();
        NativeControls ui = new NativeControls(owner, batch);
        int modal = ui.node("modal", "fullscreen", false, "chrome", "guest", "dismissible", true,
            "on", new String[] {"dismiss", "exitEnd"});
        int frame = ui.node("view", "width", "fill", "height", "fill", "direction", "column",
            "justify", "center", "align", "center", "padding", NativeControls.insets(20, 24));
        long panelSurface = (ui.card >>> 24) == 0xff ? ui.card
            : NativeControls.color(owner.isDark() ? 0xff27272d : 0xffffffff);
        int panel = ui.node("view", "direction", "column", "width", panelWidth(),
            "background", panelSurface, "radius", 16, "clip", true, "padding", NativeControls.insets(20, 12));
        Session session = new Session(profile, user, modal, ui);
        int heading = ui.node("view", "width", "fill", "direction", "row", "align", "center");
        int titleSlot = ui.node("view", "direction", "column", "flex", 1);
        session.title = ui.label("Reviews", 20, 700, ui.text);
        ui.batch.children(titleSlot, session.title);
        ui.batch.children(heading, titleSlot, ui.link("Close", (event, payload) -> close(session)));
        session.content = ui.column(8, NativeControls.insets(0, 4));
        int scroll = ui.node("scroll", "width", "fill", "maxHeight", 380);
        batch.children(scroll, session.content);
        batch.children(panel, heading, scroll);
        batch.children(frame, panel);
        batch.children(modal, frame);
        owner.submit(batch);
        open = session;
        owner.events.putAll(ui.actions);
        Log.i("DiscordX", "ReviewDB opened a renderer-owned Phoenix modal");
        CachedPage cached = recent.get(user);
        if (token == null && cached != null && SystemClock.uptimeMillis() - cached.savedAt < 30_000) {
            session.page = cached.page;
            draw(session, "");
        } else load(session, 0);
    }

    private int panelWidth() {
        float density = owner.activity.getResources().getDisplayMetrics().density;
        int pixels = owner.activity.getWindow().getDecorView().getWidth();
        if (pixels == 0) pixels = owner.activity.getResources().getDisplayMetrics().widthPixels;
        return Math.min(448, Math.max(180, Math.round(pixels / density) - 40));
    }

    private void load(Session session, int offset) throws ReflectiveOperationException {
        run(session, session.page == null ? "Loading reviews…" : "", () -> {
            ReviewDB.Page page = ReviewDB.fetch(session.user, offset);
            String activeToken = token;
            Map<Integer, Boolean> votes = activeToken == null ? Collections.emptyMap() : ReviewDB.votes(session.user, activeToken);
            return () -> {
                session.page = page;
                session.offset = offset;
                session.votes = votes;
                if (offset == 0) {
                    if (recent.size() == 12 && !recent.containsKey(session.user)) recent.remove(recent.keySet().iterator().next());
                    recent.put(session.user, new CachedPage(page));
                }
                draw(session, "");
                Log.i("DiscordX", "ReviewDB rendered " + page.reviews.size() + " review entries");
            };
        });
    }

    private void draw(Session session, String status) throws ReflectiveOperationException {
        if (open != session) return;
        PhoenixRender.Builder batch = owner.builder();
        NativeControls ui = new NativeControls(owner, batch);
        ArrayList<Integer> rows = new ArrayList<>();
        ReviewDB.Page page = session.page;
        if (page != null) {
            batch.update(session.title, "text", page.total > 0 ? "Reviews · " + page.total : "Reviews");
            ReviewDB.Review own = null;
            for (ReviewDB.Review review : page.reviews) {
                if (review.type == ReviewDB.SYSTEM_REVIEW || !session.showBlocked && blocked(review.authorId)) continue;
                rows.add(review(ui, session, review));
                if (own(review.authorId)) own = review;
            }
            if (page.optedOut) rows.add(ui.label("This user has opted out of ReviewDB.", 15, 400, ui.muted));
            else if (rows.isEmpty()) rows.add(ui.label("No public reviews.", 15, 400, ui.muted));
            if (session.offset > 0 || page.hasNext) {
                int navigation = ui.node("view", "direction", "row", "gap", 8);
                if (session.offset != 0 && page.hasNext) batch.children(navigation,
                    ui.link("First page", (event, payload) -> load(session, 0)),
                    ui.link("Next page", (event, payload) -> load(session, page.nextOffset)));
                else batch.children(navigation, page.hasNext
                    ? ui.link("Next page", (event, payload) -> load(session, page.nextOffset))
                    : ui.link("First page", (event, payload) -> load(session, 0)));
                rows.add(navigation);
            }
            if (account != null && !account.blocked.isEmpty()) rows.add(ui.link(session.showBlocked
                ? "Hide blocked reviewers" : "Show blocked reviewers", (event, payload) -> {
                    session.showBlocked = !session.showBlocked;
                    draw(session, "");
                }));
            if (account != null && !page.optedOut && !own(session.user) && session.offset == 0) {
                String[] draft = {own == null ? "" : own.comment};
                rows.add(ui.input("Write a review", draft[0], true, (event, payload) -> {
                    String text = owner.inputText(payload);
                    if (text != null) draft[0] = text;
                }));
                rows.add(ui.link(own == null ? "Post review" : "Save review", (event, payload) ->
                    mutate(session, () -> ReviewDB.submit(session.user, draft[0], token))));
            }
        }
        if (!status.isEmpty()) rows.add(ui.label(status, 14, 400, ui.muted));
        if (page == null || !page.optedOut) rows.add(ui.link(token == null ? "Sign in to ReviewDB" : "Sign out",
            (event, payload) -> {
                if (token == null) signIn(session);
                else {
                    token = null;
                    account = null;
                    load(session, session.offset);
                }
            }));
        batch.children(session.content, PhoenixRender.integers(rows));
        if (session.body != null) owner.discard(batch, session.body);
        owner.submit(batch);
        session.body = ui;
        owner.events.putAll(ui.actions);
    }

    private int review(NativeControls ui, Session session, ReviewDB.Review review) throws ReflectiveOperationException {
        int item = ui.column(4, NativeControls.insets(0, 8));
        boolean hasProfile = MessageLogger.snowflake(review.authorId);
        int author = hasProfile ? ui.node("view", "direction", "row", "align", "center", "gap", 10, "minHeight", 48,
            "on", NativeControls.PRESS, "role", "button", "accessibilityLabel", "Open " + review.author + "’s profile")
            : ui.node("view", "direction", "row", "align", "center", "gap", 10);
        if (hasProfile) ui.actions.put(author, (event, payload) -> openProfile(session, review.authorId));
        int details = ui.node("view", "direction", "column", "flex", 1, "gap", 2);
        int name = ui.label(review.author, 15, 600, ui.text);
        if (review.timestamp > 0) ui.batch.children(details, name,
            ui.label(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(review.timestamp * 1000)), 12, 400, ui.muted));
        else ui.batch.children(details, name);
        String avatar = avatar(review.avatar);
        if (avatar == null) ui.batch.children(author, details);
        else ui.batch.children(author, ui.node("image", "uri", avatar, "width", 36, "height", 36,
            "radius", 18, "clip", true, "scale", "fit"), details);
        int comment = ui.label(review.comment, 15, 400, ui.text);
        if (review.id <= 0) ui.batch.children(item, author, comment);
        else {
            int actions = ui.node("view", "direction", "row", "align", "center", "gap", 6);
            Boolean vote = session.votes.get(review.id);
            ArrayList<Integer> buttons = new ArrayList<>();
            buttons.add(ui.link(Boolean.TRUE.equals(vote) ? "Upvoted" : "Upvote", (event, payload) -> vote(session, review.id, true)));
            buttons.add(ui.label(Integer.toString(review.score), 14, 700, ui.text));
            buttons.add(ui.link(Boolean.FALSE.equals(vote) ? "Downvoted" : "Downvote", (event, payload) -> vote(session, review.id, false)));
            if (account != null) buttons.add(ui.link("More", (event, payload) -> {
                session.selected = session.selected == review.id ? 0 : review.id;
                draw(session, "");
            }));
            ui.batch.children(actions, PhoenixRender.integers(buttons));
            if (session.selected == review.id && account != null) {
                int more = ui.column(0, NativeControls.insets(0, 0));
                ArrayList<Integer> options = new ArrayList<>();
                boolean own = own(review.authorId);
                String verb = own ? "Delete review" : "Report review";
                options.add(ui.link(session.confirm == review.id ? "Confirm " + verb.toLowerCase(java.util.Locale.ROOT) : verb,
                    (event, payload) -> {
                        if (session.confirm != review.id) {
                            session.confirm = review.id;
                            draw(session, "");
                        } else mutate(session, () -> {
                            if (own) ReviewDB.remove(review.id, token);
                            else ReviewDB.report(review.id, token);
                        });
                    }));
                if (!own && MessageLogger.snowflake(review.authorId)) {
                    boolean blocked = blocked(review.authorId);
                    options.add(ui.link(blocked ? "Unblock reviewer" : "Block reviewer", (event, payload) ->
                        run(session, "Updating blocked reviewers…", () -> {
                            ReviewDB.setBlocked(review.authorId, !blocked, token);
                            return () -> {
                                if (blocked) account.blocked.remove(review.authorId);
                                else account.blocked.add(review.authorId);
                                draw(session, "");
                            };
                        })));
                }
                ui.batch.children(more, PhoenixRender.integers(options));
                ui.batch.children(item, author, comment, actions, more);
            } else ui.batch.children(item, author, comment, actions);
        }
        return item;
    }

    private void vote(Session session, int id, boolean up) throws ReflectiveOperationException {
        if (token == null) {
            signIn(session);
            return;
        }
        Boolean previous = session.votes.get(id);
        mutate(session, () -> ReviewDB.vote(id, Boolean.valueOf(up).equals(previous) ? null : up, token));
    }

    private void signIn(Session session) throws ReflectiveOperationException {
        boolean started = ReviewAuthorization.open(owner.activity, code -> {
            try {
                run(session, "Signing in…", () -> {
                    String signedIn = ReviewDB.authorize(code);
                    ReviewDB.Account user = ReviewDB.account(signedIn);
                    return () -> {
                        token = signedIn;
                        account = user;
                        load(session, session.offset);
                    };
                });
            } catch (ReflectiveOperationException error) {
                owner.failure("Could not render ReviewDB sign-in", error);
            }
        });
        if (!started) draw(session, "Couldn’t open ReviewDB sign-in.");
    }

    private void mutate(Session session, Update mutation) throws ReflectiveOperationException {
        run(session, "Saving…", () -> {
            mutation.run();
            return () -> {
                session.selected = 0;
                session.confirm = 0;
                recent.remove(session.user);
                load(session, session.offset);
            };
        });
    }

    private void run(Session session, String status, Request request) throws ReflectiveOperationException {
        if (session.busy || open != session) return;
        if (!status.isEmpty()) draw(session, status);
        session.busy = true;
        REQUESTS.execute(() -> {
            Update next;
            try {
                next = request.run();
            } catch (Exception error) {
                String message = error instanceof IOException ? error.getMessage() : "ReviewDB could not complete the request.";
                next = () -> draw(session, message);
            }
            Update update = next;
            owner.main.post(() -> {
                if (open != session || owner.activity.isDestroyed()) return;
                session.busy = false;
                try {
                    update.run();
                } catch (Exception error) {
                    owner.failure("Could not update the Phoenix reviews modal", error);
                }
            });
        });
    }

    private void close(Session session) {
        if (open != session) return;
        open = null;
        try {
            PhoenixRender.Builder batch = owner.builder();
            owner.discard(batch, session.ui);
            if (session.body != null) owner.discard(batch, session.body);
            owner.submit(batch);
        } catch (ReflectiveOperationException error) {
            owner.failure("Could not close the Phoenix reviews modal", error);
        }
    }

    private void openProfile(Session session, String user) {
        try {
            owner.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/users/" + user))
                .setPackage(owner.activity.getPackageName()));
            close(session);
        } catch (ActivityNotFoundException ignored) {
            // The current profile stays open if this build has no matching route.
        }
    }

    boolean overlayEvent(int id, String event) {
        Session session = open;
        if (session != null && session.modal == id) owner.main.post(() -> close(session));
        return id >= 0x70000000;
    }

    void hostRemoved(int id) {
        Session session = open;
        if (session != null && session.profile == id) owner.main.post(() -> close(session));
    }

    void reset() { open = null; }

    private static boolean own(String user) { return account != null && account.id.equals(user); }
    private static boolean blocked(String user) { return account != null && account.blocked.contains(user); }

    private static String avatar(String url) {
        Uri uri = Uri.parse(url);
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && "cdn.discordapp.com".equals(uri.getHost()) ? url : null;
    }

    private interface Update { void run() throws Exception; }
    private interface Request { Update run() throws Exception; }

    private static final class CachedPage {
        final ReviewDB.Page page;
        final long savedAt = SystemClock.uptimeMillis();
        CachedPage(ReviewDB.Page page) { this.page = page; }
    }

    private static final class Session {
        final int profile, modal;
        final String user;
        final NativeControls ui;
        int content, title, selected, confirm, offset;
        boolean showBlocked, busy;
        ReviewDB.Page page;
        Map<Integer, Boolean> votes = Collections.emptyMap();
        NativeControls body;

        Session(int profile, String user, int modal, NativeControls ui) {
            this.profile = profile;
            this.user = user;
            this.modal = modal;
            this.ui = ui;
        }
    }
}
