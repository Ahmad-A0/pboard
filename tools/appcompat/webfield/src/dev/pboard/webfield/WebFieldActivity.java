package dev.pboard.webfield;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The app-compatibility test page (assets/fields.html) in the system WebView: one field per load, chosen by the
 * "field" extra (text, password, number, numeric, textarea, editable, upper, chat).
 *   am start -S -n dev.pboard.webfield/.WebFieldActivity --es field textarea
 */
public class WebFieldActivity extends Activity {
    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        load(getIntent());
    }

    /** A launcher intent (coming back from recents/home) keeps the page as it is; only an explicit field reloads it. */
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (i.hasExtra("field")) load(i); }

    private void load(Intent i) {
        String f = i.getStringExtra("field");
        web.loadUrl("file:///android_asset/fields.html#" + (f == null ? "text" : f));
    }
}
