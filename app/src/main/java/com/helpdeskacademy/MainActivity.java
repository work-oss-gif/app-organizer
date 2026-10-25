package com.helpdeskacademy;

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

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#18233A"));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      // the lessons and quizzes run in JavaScript
        s.setDomStorageEnabled(true);      // lets the page save your progress
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient()); // keep pages inside the app
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
