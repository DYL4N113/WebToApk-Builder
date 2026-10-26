package com.dyl4n.webtoapk.shell;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String TAG = "WebViewShell";
    private static final String LOCAL_INDEX_URL = "file:///android_asset/www/index.html";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(true);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        setContentView(webView);

        String websiteUrl = readWebsiteUrl();
        webView.loadUrl(websiteUrl == null ? LOCAL_INDEX_URL : websiteUrl);
    }

    private String readWebsiteUrl() {
        try (InputStream input = getAssets().open("url.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder contents = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (contents.length() > 0) {
                    contents.append('\n');
                }
                contents.append(line);
            }
            String url = contents.toString().trim();
            return url.isEmpty() ? null : url;
        } catch (IOException error) {
            Log.w(TAG, "Could not read assets/url.txt; loading local index.html.", error);
            return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
