package discordx;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.UUID;
import java.util.function.Consumer;

final class ReviewAuthorization {
    static boolean open(Activity activity, Consumer<String> authorized) {
        String state = UUID.randomUUID().toString();
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);
        Button cancel = new Button(activity);
        cancel.setText("Cancel sign-in");
        content.addView(cancel, new LinearLayout.LayoutParams(-1, -2));
        WebView web = new WebView(activity);
        int height = activity.getResources().getDisplayMetrics().heightPixels / 2;
        content.addView(web, new LinearLayout.LayoutParams(-1, height));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        PhoenixModal modal = PhoenixModal.open(activity, content);
        if (modal == null) {
            web.destroy();
            return false;
        }
        cancel.setOnClickListener(view -> modal.close());
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                content.removeView(web);
                web.stopLoading();
                web.destroy();
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri url = request.getUrl();
                if (url.getUserInfo() != null || !"https".equals(url.getScheme())) return true;
                if ("discord.com".equals(url.getHost())) return false;
                if ("manti.vendicated.dev".equals(url.getHost()) && "/api/reviewdb/auth".equals(url.getPath())) {
                    String code = url.getQueryParameter("code");
                    view.post(() -> {
                        modal.close();
                        if (state.equals(url.getQueryParameter("state")) && code != null && !code.isEmpty()) authorized.accept(code);
                    });
                }
                return true;
            }
        });
        Uri url = Uri.parse("https://discord.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", "915703782174752809")
            .appendQueryParameter("redirect_uri", ReviewDB.CALLBACK)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "identify")
            .appendQueryParameter("state", state).build();
        web.loadUrl(url.toString());
        return true;
    }
}
