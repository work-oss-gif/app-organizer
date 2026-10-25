package com.sysadminpro;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The whole app is one screen: a WebView showing the lessons page
 * (app/src/main/assets/index.html). Progress (XP, streak, finished lessons)
 * is saved on the phone by the page itself, so it stays after closing the app.
 */
public class MainActivity extends Activity {

    private static final int DARK_BG = 0xFF0C1716;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Always dark: dark status and navigation bars with light icons.
        getWindow().setStatusBarColor(DARK_BG);
        getWindow().setNavigationBarColor(DARK_BG);

        web = new WebView(this);
        web.setBackgroundColor(DARK_BG); // no white flash while the page loads
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      // the lessons and quizzes run in JavaScript
        s.setDomStorageEnabled(true);      // lets the page save your progress
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient() { // keep pages inside the app
            @Override
            public void onPageFinished(WebView view, String url) {
                // Make sure the page always uses its dark colors.
                view.evaluateJavascript("document.documentElement.setAttribute('data-theme','dark')", null);
            }
        });
        setContentView(web);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    /** Back button: closes an open lesson first; on the home screen it exits. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.hdaBack ? window.hdaBack() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if (!"true".equals(handled)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }
}
