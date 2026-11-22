package discordx.loader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

public interface ModuleApi {
    int ABI = 5;

    int abi();
    void prepare(Activity activity);
    void attach(Activity activity);
    void detach(Activity activity);
    boolean onActivityResult(int request, int result, Intent data);
    boolean handleKey(Activity activity, KeyEvent event);
    Uri cleanLink(Context context, Uri uri);
    Intent browserIntent(Intent original);
    CharSequence cleanCopiedText(CharSequence text);
    boolean reduceMotion(boolean systemPreference);
    void themeFill(GradientDrawable view, int color);
    void themeRoot(View view, int color);
    void themeText(TextView view);
    int foreground(int color);
    int[] gradient(int[] colors);
    void socketOpened(Object owner, int id, String url);
    void socketClosed(Object owner, int id);
    byte[] onFrame(Object owner, int id, byte[] bytes);
    Object interceptHttp(Object request);
    Object onHttpResponse(Object response);
    CharSequence decorateMessageText(CharSequence text);
    CharSequence cleanMessageText(CharSequence text);
    boolean onOverlayEvent(View handle, String event);
    Object onRenderBatch(Object renderer, Object batch);
    boolean onViewEvent(int id, String event, Object payload, Object completion);
}
