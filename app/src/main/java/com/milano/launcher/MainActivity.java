package com.milano.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Milano Launcher: mở trang launcher web toàn màn hình làm màn hình Home của Android TV / Google TV.
 * - Cho trang dùng micro (Hỏi AI) và vị trí (thời tiết) mà không cần Fully PLUS.
 * - Cầu nối JavaScript "MilanoApp": mở app, mở intent, đọc to (TTS), liệt kê app đã cài.
 * - Mất mạng thì mở bản launcher đi kèm trong app, có mạng lại thì tự về bản trên web.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 11;
    private static final int REQ_GEO = 12;
    private static final int REQ_START = 13;
    private static final String FALLBACK = "file:///android_asset/index.html";

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean onFallback = false;
    private PermissionRequest pendingMic;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Máy chủ Karaoke chạy trong app (điện thoại quét QR để chọn bài). Giữ chung cho cả tiến trình. */
    private static KaraokeServer karaoke;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        hideSystemUi();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MilanoLauncher/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "MilanoApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(BuildConfig.LAUNCHER_URL);

        askStartPermissions();
        startKaraoke();
    }

    /* ---------------- Karaoke ---------------- */

    private synchronized KaraokeServer karaoke() {
        if (karaoke == null) {
            final android.content.Context app = getApplicationContext();
            karaoke = new KaraokeServer(name -> app.getAssets().open(name), app.getFilesDir(), "KARAOKE");
        }
        return karaoke;
    }

    /** Mở cổng 8686 ở luồng nền để điện thoại vào được ngay khi launcher chạy. */
    private void startKaraoke() {
        new Thread(() -> {
            try { karaoke().start(8686); } catch (Exception ignored) { }
        }, "karaoke-start").start();
    }

    /* ---------------- Quyền micro + vị trí ---------------- */

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Hỏi trước một lần lúc mở app để lúc dùng Hỏi AI không phải chờ hộp thoại. */
    private void askStartPermissions() {
        List<String> need = new ArrayList<>();
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_START);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (pendingMic != null && (code == REQ_MIC || code == REQ_START)) {
            if (has(Manifest.permission.RECORD_AUDIO)) pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingMic.deny();
            pendingMic = null;
        }
        if (pendingGeo != null && (code == REQ_GEO || code == REQ_START)) {
            pendingGeo.invoke(pendingGeoOrigin, has(Manifest.permission.ACCESS_COARSE_LOCATION), false);
            pendingGeo = null;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                boolean mic = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) mic = true;
                }
                if (!mic) { request.deny(); return; }
                if (has(Manifest.permission.RECORD_AUDIO)) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingMic = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            });
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                callback.invoke(origin, true, false);
            } else {
                pendingGeo = callback;
                pendingGeoOrigin = origin;
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
            }
        }
    }

    /* ---------------- Tải trang, link mở app, mất mạng ---------------- */

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("http://") || url.startsWith("https://")
                    || url.startsWith("file:") || url.startsWith("about:") || url.startsWith("data:")) {
                return false;
            }
            if (!openUri(url)) toast("Không mở được ứng dụng");
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && !onFallback) {
                onFallback = true;
                view.loadUrl(FALLBACK);
                scheduleOnlineCheck();
            }
        }
    }

    /** Đang dùng bản dự phòng: cứ 30 giây xem có mạng lại chưa, có thì về bản trên web. */
    private void scheduleOnlineCheck() {
        handler.postDelayed(() -> {
            if (!onFallback) return;
            if (isOnline()) {
                onFallback = false;
                web.loadUrl(BuildConfig.LAUNCHER_URL);
            } else {
                scheduleOnlineCheck();
            }
        }, 30_000);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- Mở app ---------------- */

    private boolean openApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        Intent it = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (it == null) it = pm.getLaunchIntentForPackage(pkg);
        if (it == null) return false;
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return start(it);
    }

    /** Mở trợ lý của hệ thống (Gemini / Google Assistant) như khi bấm nút trợ lý trên remote. */
    private boolean openAssistant() {
        try {
            if (showAssist(null)) return true;
        } catch (Exception ignored) {
        }
        String[] actions = {
                Intent.ACTION_ASSIST,
                Intent.ACTION_VOICE_COMMAND,
                "android.intent.action.VOICE_ASSIST",
                "android.speech.action.VOICE_SEARCH_HANDS_FREE",
                "android.speech.action.WEB_SEARCH",
        };
        for (String a : actions) {
            Intent it = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(it)) return true;
        }
        // Cuối cùng: mở app tìm kiếm giọng nói của Google TV
        return openApp("com.google.android.katniss") || openApp("com.google.android.tvrecommendations");
    }

    private boolean openUri(String uri) {
        Intent it;
        try {
            it = uri.startsWith("intent:")
                    ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        } catch (Exception e) {
            return false;
        }
        it.setSelector(null);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(it)) return true;
        // Intent không mở được nhưng có tên gói: mở app đó theo cách thường
        return openApp(it.getPackage());
    }

    private boolean start(Intent it) {
        try {
            startActivity(it);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- Cầu nối cho trang web ---------------- */

    private class Bridge {
        @JavascriptInterface
        public boolean openApp(String pkg) {
            return MainActivity.this.openApp(pkg);
        }

        @JavascriptInterface
        public boolean openAssistant() {
            final boolean[] ok = {false};
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            // showAssist phải chạy trên luồng giao diện; chờ tối đa 1,5 giây lấy kết quả
            runOnUiThread(() -> {
                try { ok[0] = MainActivity.this.openAssistant(); } finally { done.countDown(); }
            });
            try { done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            return ok[0];
        }

        @JavascriptInterface
        public boolean openIntent(String uri) {
            return MainActivity.this.openUri(uri);
        }

        @JavascriptInterface
        public void speak(String text, String lang) {
            if (!ttsReady || text == null) return;
            try {
                Locale loc = Locale.forLanguageTag(lang == null || lang.isEmpty() ? "vi-VN" : lang);
                int r = tts.setLanguage(loc);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "milano");
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void stopSpeak() {
            try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        }

        /** Danh sách app mở được: [{"name":"YouTube","pkg":"com.google..."}], xếp theo tên. */
        @JavascriptInterface
        public String getApps() {
            PackageManager pm = getPackageManager();
            Map<String, String> apps = new LinkedHashMap<>();
            String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || apps.containsKey(pkg)) continue;
                    apps.put(pkg, String.valueOf(ri.loadLabel(pm)));
                }
            }
            List<String[]> list = new ArrayList<>();
            for (Map.Entry<String, String> e : apps.entrySet()) list.add(new String[]{e.getValue(), e.getKey()});
            Collections.sort(list, (a, b) -> a[0].compareToIgnoreCase(b[0]));
            JSONArray arr = new JSONArray();
            try {
                for (String[] a : list) arr.put(new JSONObject().put("name", a[0]).put("pkg", a[1]));
            } catch (Exception ignored) {
            }
            return arr.toString();
        }

        /** Địa chỉ trang Karaoke trên TV (tự bật máy chủ nếu chưa chạy). */
        @JavascriptInterface
        public String karaokeUrl() {
            try {
                KaraokeServer k = karaoke();
                k.start(8686);
                return k.localTvUrl();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> {
                onFallback = false;
                web.loadUrl(BuildConfig.LAUNCHER_URL);
            });
        }
    }

    /** Trang đang mở có phải launcher (bản web hoặc bản đi kèm) không. */
    private boolean onLauncher() {
        String u = web.getUrl();
        if (u == null) return true;
        String home = BuildConfig.LAUNCHER_URL.replaceAll("/+$", "");
        return u.startsWith(home) || u.startsWith(FALLBACK);
    }

    private void goHome() {
        runOnUiThread(() -> {
            onFallback = false;
            web.loadUrl(BuildConfig.LAUNCHER_URL);
        });
    }

    /* ---------------- Phím remote, vòng đời ---------------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Launcher là màn hình Home: nút Back không thoát app, chỉ đóng bảng đang mở trên trang
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("(window.milanoBack && window.milanoBack()) ? 1 : 0", handled -> {
                    // Đang ở trang khác (vd. Karaoke) và trang không tự xử lý Back: quay về launcher
                    if (!"1".equals(handled) && !onLauncher()) goHome();
                });
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Bấm Home: đang ở trang khác (vd. Karaoke) thì về launcher, còn không thì đóng bảng đang mở
        if (web == null) return;
        if (!onLauncher()) goHome();
        else web.evaluateJavascript("window.milanoHome && window.milanoHome()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (web != null) {
            web.onResume();
            web.requestFocus();
            web.evaluateJavascript("window.milanoResume && window.milanoResume()", null);
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
