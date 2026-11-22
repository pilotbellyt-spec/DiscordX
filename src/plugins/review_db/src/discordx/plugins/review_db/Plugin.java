package discordx.plugins.review_db;

import android.content.Intent;
import android.net.Uri;
import discordx.api.PluginContext;
import discordx.api.PluginUi;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

public final class Plugin implements discordx.api.Plugin {
    private PluginContext context;
    private String token;
    private ReviewDB.Account account;
    private Session open;

    @Override public void start(PluginContext context) {
        this.context = context;
        context.ui().profileAction("Reviews", user -> {
            Session session = new Session(user);
            open = session;
            context.ui().open("Reviews", page -> draw(session, page));
            load(session, 0);
        });
    }

    private void draw(Session session, PluginUi.Controls ui) {
        ReviewDB.Page page = session.page;
        ui.row(toolbar -> {
            toolbar.column(summary -> {
                if (page != null) summary.secondary(page.total + (page.total == 1 ? " review" : " reviews"));
            });
            toolbar.button("Refresh", () -> load(session, session.offset));
            toolbar.button(token == null ? "Sign in" : "Sign out", () -> {
                if (token == null) signIn(session);
                else { token = null; account = null; session.draft = null; load(session, session.offset); }
            });
        });
        if (!session.status.isEmpty()) ui.secondary(session.status);
        if (page == null) return;

        ReviewDB.Review ownReview = null;
        for (ReviewDB.Review review : page.reviews) if (own(review.authorId)) ownReview = review;
        if (token != null && !page.optedOut && !own(session.user)) {
            if (session.draft == null) session.draft = ownReview == null ? "" : ownReview.comment;
            boolean editing = ownReview != null;
            ui.card(composer -> {
                composer.input("Your review", session.draft, true, value -> session.draft = value);
                composer.row(actions -> actions.button(editing ? "Save" : "Post review",
                    () -> mutate(session, () -> ReviewDB.submit(session.user, session.draft, token))));
            });
        }
        if (account != null && !account.blocked.isEmpty()) ui.toggle("Show blocked", session.showBlocked, value -> {
            session.showBlocked = value; context.ui().refresh();
        });
        ui.divider();
        int shown = 0;
        for (ReviewDB.Review review : page.reviews) {
            if (review.type == ReviewDB.SYSTEM_REVIEW || !session.showBlocked && blocked(review.authorId)) continue;
            shown++;
            reviewCard(session, review, ui);
        }
        if (page.optedOut) ui.secondary("Opted out");
        else if (shown == 0) ui.secondary("No reviews");
        if (session.offset > 0 || page.hasNext) ui.row(navigation -> {
            if (session.offset > 0) navigation.button("First page", () -> load(session, 0));
            if (page.hasNext) navigation.button("Next page", () -> load(session, page.nextOffset));
        });
    }

    private void reviewCard(Session session, ReviewDB.Review review, PluginUi.Controls ui) {
        ui.card(card -> {
            card.row(header -> {
                if (review.avatar != null && review.avatar.startsWith("https://cdn.discordapp.com/"))
                    header.image(review.avatar, review.author);
                header.column(author -> {
                    if (review.authorId != null && review.authorId.matches("[1-9][0-9]{16,19}"))
                        author.button(review.author, () -> context.activity().startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://discord.com/users/" + review.authorId)).setPackage(context.application().getPackageName())));
                    else author.heading(review.author);
                    author.secondary(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                        .format(new Date(review.timestamp * 1000L)));
                });
                header.heading((review.score > 0 ? "+" : "") + review.score);
            });
            card.text(review.comment);
            if (token == null) return;
            Boolean selected = session.votes.get(review.id);
            card.row(actions -> {
                actions.button(Boolean.TRUE.equals(selected) ? "Upvoted" : "Upvote", () -> vote(session, review.id, true));
                actions.button(Boolean.FALSE.equals(selected) ? "Downvoted" : "Downvote", () -> vote(session, review.id, false));
                actions.button(session.more == review.id ? "Less" : "More", () -> {
                    session.more = session.more == review.id ? 0 : review.id;
                    session.confirm = 0;
                    context.ui().refresh();
                });
            });
            if (session.more != review.id) return;
            card.divider();
            card.row(actions -> {
                boolean isOwn = own(review.authorId);
                String action = isOwn ? "Delete" : "Report";
                actions.button(session.confirm == review.id ? "Confirm " + action.toLowerCase(java.util.Locale.ROOT) : action, () -> {
                    if (session.confirm != review.id) { session.confirm = review.id; context.ui().refresh(); return; }
                    mutate(session, () -> { if (isOwn) ReviewDB.remove(review.id, token); else ReviewDB.report(review.id, token); });
                });
                if (!isOwn) actions.button(blocked(review.authorId) ? "Unblock" : "Block", () ->
                    run(session, () -> { ReviewDB.setBlocked(review.authorId, !blocked(review.authorId), token); return ReviewDB.account(token); },
                        value -> { account = value; load(session, session.offset); }));
            });
        });
    }

    private void load(Session session, int offset) {
        run(session, () -> new Loaded(ReviewDB.fetch(session.user, offset), token == null
            ? Collections.emptyMap() : ReviewDB.votes(session.user, token)), value -> {
                session.page = value.page;
                session.votes = value.votes;
                session.offset = offset;
                context.ui().refresh();
            });
    }

    private void vote(Session session, int id, boolean up) {
        Boolean current = session.votes.get(id);
        mutate(session, () -> ReviewDB.vote(id, Boolean.valueOf(up).equals(current) ? null : up, token));
    }

    private void signIn(Session session) {
        ReviewAuthorization.open(context, code -> run(session, () -> {
            String value = ReviewDB.authorize(code);
            return new SignedIn(value, ReviewDB.account(value));
        }, value -> { token = value.token; account = value.account; load(session, session.offset); }));
    }

    private void mutate(Session session, Update mutation) {
        run(session, () -> { mutation.run(); return Boolean.TRUE; }, ignored -> {
            session.confirm = 0; session.draft = null; load(session, session.offset);
        });
    }

    private <T> void run(Session session, java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> done) {
        if (session.busy || open != session) return;
        session.busy = true;
        session.status = "Loading…";
        context.ui().refresh();
        context.runAsync(work, value -> {
            if (open != session) return;
            session.busy = false; session.status = ""; done.accept(value); context.ui().refresh();
        }, error -> {
            if (open != session) return;
            session.busy = false; session.status = error.getMessage() == null ? "ReviewDB request failed." : error.getMessage(); context.ui().refresh();
        });
    }

    @Override public void stop() { token = null; account = null; open = null; context = null; }
    private boolean own(String id) { return account != null && account.id.equals(id); }
    private boolean blocked(String id) { return account != null && account.blocked.contains(id); }
    private interface Update { void run() throws Exception; }
    private static final class Session {
        final String user;
        ReviewDB.Page page;
        Map<Integer, Boolean> votes = Collections.emptyMap();
        int offset, confirm, more;
        boolean busy, showBlocked;
        String draft, status = "Loading…";
        Session(String user) { this.user = user; }
    }
    private static final class Loaded {
        final ReviewDB.Page page;
        final Map<Integer, Boolean> votes;
        Loaded(ReviewDB.Page page, Map<Integer, Boolean> votes) { this.page = page; this.votes = votes; }
    }
    private static final class SignedIn {
        final String token;
        final ReviewDB.Account account;
        SignedIn(String token, ReviewDB.Account account) { this.token = token; this.account = account; }
    }
}
