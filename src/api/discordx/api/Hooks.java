package discordx.api;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;

public final class Hooks {
    private Hooks() {}
    public static final Hook<UnaryOperator<Uri>> OPEN_LINK = new Hook<>("open_link");
    public static final Hook<UnaryOperator<Intent>> BROWSER_INTENT = new Hook<>("browser_intent");
    public static final Hook<UnaryOperator<CharSequence>> CLIPBOARD = new Hook<>("clipboard");
    public static final Hook<UnaryOperator<CharSequence>> DISPLAY_TEXT = new Hook<>("display_text");
    public static final Hook<UnaryOperator<CharSequence>> COMPOSER_TEXT = new Hook<>("composer_text");
    public static final Hook<UnaryOperator<Boolean>> REDUCE_MOTION = new Hook<>("reduce_motion");
    public static final Hook<IntUnaryOperator> FILL_COLOR = new Hook<>("fill_color");
    public static final Hook<IntUnaryOperator> TEXT_COLOR = new Hook<>("text_color");
    public static final Hook<IntUnaryOperator> FOREGROUND_COLOR = new Hook<>("foreground_color");
    public static final Hook<UnaryOperator<int[]>> GRADIENT = new Hook<>("gradient");
    public static final Hook<java.util.function.Function<String, String>> BEFORE_SEND = new Hook<>("before_send");
    /** Submission from the native picker for a command contributed by DiscordX Local. */
    public static final Hook<java.util.function.BiConsumer<String, String>> LOCAL_COMMAND = new Hook<>("local_command");
    public static final Hook<Gateway> GATEWAY = new Hook<>("gateway");
    public static final Hook<HttpResponse> HTTP_RESPONSE = new Hook<>("http_response");
    public static final Hook<java.util.function.Function<Request, Reply>> HTTP_REQUEST = new Hook<>("http_request");
    public static final Hook<HttpBody> HTTP_BODY = new Hook<>("http_body");
    public static final Hook<GatewayJson> GATEWAY_JSON = new Hook<>("gateway_json");
    public static final Hook<ActivityEvents> ACTIVITY = new Hook<>("activity");

    public static final Hook<java.util.function.Consumer<Navigation>> NAVIGATION = new Hook<>("navigation");
    public static final class Navigation {
        public final String event, screen;
        public Navigation(String event, String screen) { this.event = event; this.screen = screen; }
    }
    public interface Gateway {
        default void opened(Connection connection) {}
        default void closed(Connection connection) {}
        byte[] frame(Connection connection, byte[] bytes);
    }
    public interface HttpResponse { void received(String url, byte[] bytes); }
    public interface HttpBody { byte[] transform(String url, byte[] bytes); }
    public interface GatewayJson { org.json.JSONObject transform(Connection connection, org.json.JSONObject dispatch); }
    public static final class Request {
        public final String url, method;
        public Request(String url, String method) { this.url = url; this.method = method; }
    }
    public static final class Reply {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public Reply(int status, String contentType, byte[] body) {
            if (status < 200 || status > 599 || body == null || body.length > 2 * 1024 * 1024
                    || status == 204 && body.length != 0) throw new IllegalArgumentException("Invalid local HTTP reply");
            this.status = status; this.contentType = contentType; this.body = body.clone();
        }
    }
    public interface ActivityEvents {
        void attached(Activity activity);
        default void detached(Activity activity) {}
    }
    public static final class Connection {
        public final String url;
        public Connection(String url) { this.url = url; }
    }
}
