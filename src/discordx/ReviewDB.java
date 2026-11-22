package discordx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ReviewDB {
    static final int SYSTEM_REVIEW = 3;
    static final String ORIGIN = "https://manti.vendicated.dev";
    static final String CALLBACK = ORIGIN + "/api/reviewdb/auth";
    private static final String ENDPOINT = ORIGIN + "/api/reviewdb";
    private static final Pattern PROFILE = Pattern.compile(
        "(?:https://(?:www\\.)?discord\\.com/users/)?([1-9][0-9]{16,19})/?");
    private static final int MAX_BYTES = 1024 * 1024;

    private ReviewDB() {}

    static String userId(String input) {
        Matcher match = PROFILE.matcher(input.trim());
        return match.matches() ? match.group(1) : null;
    }

    static final class Review {
        final String author;
        final String comment;
        final long timestamp;
        final int type;
        final int id, score;
        final String avatar, authorId;

        Review(String author, String comment, long timestamp, int type, int id, int score,
               String avatar, String authorId) {
            this.author = author;
            this.comment = comment;
            this.timestamp = timestamp;
            this.type = type;
            this.id = id;
            this.score = score;
            this.avatar = avatar;
            this.authorId = authorId;
        }
    }

    static final class Page {
        final List<Review> reviews;
        final boolean hasNext;
        final int nextOffset;
        final int total;
        final boolean optedOut;

        Page(List<Review> reviews, boolean hasNext, int nextOffset, int total, boolean optedOut) {
            this.reviews = reviews;
            this.hasNext = hasNext;
            this.nextOffset = nextOffset;
            this.total = total;
            this.optedOut = optedOut;
        }
    }

    static Page fetch(String id, int offset) throws IOException, JSONException {
        if (!id.equals(userId(id)) || offset < 0) throw new IllegalArgumentException("Invalid lookup");
        return parse(request("GET", "/users/" + id + "/reviews?offset=" + offset + "&limit=15&flags=2", null, null), offset);
    }

    static String authorize(String code) throws IOException, JSONException {
        String body = request("GET", "/auth?code=" + URLEncoder.encode(code, "UTF-8") + "&clientMod=DiscordX", null, null);
        String token = new JSONObject(body).optString("token");
        if (token.isEmpty() || token.length() > 4096 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IOException("ReviewDB did not return a session.");
        }
        return token;
    }

    static Account account(String token) throws IOException, JSONException {
        JSONObject current = new JSONObject(request("GET", "/users", token, null));
        String id = current.optString("discordID");
        if (!MessageLogger.snowflake(id)) throw new IOException("ReviewDB did not return an account.");
        Set<String> blocked = new HashSet<>();
        JSONArray entries = current.optJSONArray("blockedUsers");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                String user = entries.optString(i);
                if (MessageLogger.snowflake(user)) blocked.add(user);
            }
        }
        return new Account(id, blocked);
    }

    static final class Account {
        final String id;
        final Set<String> blocked;

        Account(String id, Set<String> blocked) {
            this.id = id;
            this.blocked = blocked;
        }
    }

    static Map<Integer, Boolean> votes(String id, String token) throws IOException, JSONException {
        Map<Integer, Boolean> votes = new HashMap<>();
        JSONArray items = new JSONObject(request("GET", "/users/" + id + "/reviews/votes", token, null)).optJSONArray("votes");
        if (items == null) return votes;
        for (int i = 0; i < items.length(); i++) {
            JSONObject vote = items.optJSONObject(i);
            if (vote != null && vote.optInt("reviewID") > 0) votes.put(vote.optInt("reviewID"), vote.optBoolean("isUpvote"));
        }
        return votes;
    }

    static void vote(int review, Boolean value, String token) throws IOException, JSONException {
        requireReview(review);
        JSONObject body = value == null ? null : new JSONObject().put("isUpvote", value);
        mutation(value == null ? "DELETE" : "POST", "/reviews/" + review + "/vote", token, body);
    }

    static void submit(String user, String comment, String token) throws IOException, JSONException {
        if (!MessageLogger.snowflake(user) || comment.trim().isEmpty() || comment.length() > 1000) {
            throw new IOException("Enter a review of 1–1,000 characters.");
        }
        mutation("PUT", "/users/" + user + "/reviews", token,
            new JSONObject().put("userid", user).put("comment", comment));
    }

    static void remove(int review, String token) throws IOException, JSONException {
        requireReview(review);
        mutation("DELETE", "/users/" + review + "/reviews", token, new JSONObject().put("reviewid", review));
    }

    static void report(int review, String token) throws IOException, JSONException {
        requireReview(review);
        mutation("PUT", "/reports", token, new JSONObject().put("reviewid", review));
    }

    static void setBlocked(String user, boolean blocked, String token) throws IOException, JSONException {
        if (!MessageLogger.snowflake(user)) throw new IOException("This reviewer is unavailable.");
        mutation("PATCH", "/blocks", token,
            new JSONObject().put("action", blocked ? "block" : "unblock").put("discordId", user));
    }

    private static void requireReview(int review) throws IOException {
        if (review <= 0) throw new IOException("This review cannot be changed.");
    }

    private static void mutation(String method, String path, String token, JSONObject body)
            throws IOException, JSONException {
        if (token == null) throw new IOException("Sign in to ReviewDB first.");
        String data = request(method, path, token, body);
        if (!data.isEmpty()) {
            JSONObject result = new JSONObject(data);
            if (result.has("success") && !result.optBoolean("success")) {
                throw new IOException(result.optString("message", "ReviewDB did not complete the request."));
            }
        }
    }

    private static String request(String method, String path, String token, JSONObject body) throws IOException {
        URL url = new URL(ENDPOINT + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "DiscordX/0.1 Android");
        if (token != null) connection.setRequestProperty("Authorization", token);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            if (status == 429) throw new IOException("Too many lookups. Wait a moment and try again.");
            if (status == 401 || status == 403) throw new IOException("ReviewDB declined this action. Check your sign-in.");
            if (status < 200 || status >= 300) throw new IOException("ReviewDB returned HTTP " + status + ".");
            if (status == 204) return "";
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n; (n = input.read(chunk)) != -1;) {
                    if (response.size() + n > MAX_BYTES) throw new IOException("ReviewDB sent a response that was too large.");
                    response.write(chunk, 0, n);
                }
                return new String(response.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    static Page parse(String body, int offset) throws IOException, JSONException {
        JSONObject response = new JSONObject(body);
        if (!response.optBoolean("success")) throw new IOException("ReviewDB could not complete the lookup.");
        JSONArray entries = response.optJSONArray("reviews");
        ArrayList<Review> reviews = new ArrayList<>();
        if (entries == null) return new Page(reviews, false, offset, 0, response.optBoolean("hasOptedOut"));
        boolean hasNext = response.optBoolean("hasNextPage") && entries.length() > 0;
        int count = hasNext ? Math.min(entries.length(), 50) : entries.length();
        for (int index = 0; index < count; index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null) continue;
            JSONObject sender = entry.optJSONObject("sender");
            String author = sender == null ? "ReviewDB" : sender.optString("username", "ReviewDB");
            reviews.add(new Review(author, entry.optString("comment"), entry.optLong("timestamp"),
                entry.optInt("type", 0), entry.optInt("id"), entry.optInt("score"),
                sender == null ? "" : sender.optString("profilePhoto"), sender == null ? "" : sender.optString("discordID")));
        }
        return new Page(reviews, hasNext, offset + count, response.optInt("reviewCount", reviews.size()),
            response.optBoolean("hasOptedOut"));
    }
}
