package de.andre.heimspiel;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native host for the bundled, offline Heimspiel interface. */
public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final String DEFAULT_AUDIO = "bayern-torhymne-0-5.m4a";
    private static final String PREFS = "heimspiel";
    private static final String UI_STATE = "ui_state";
    private static final String CUSTOM_AUDIO_NAME = "custom_audio_name";
    private static final int PICK_AUDIO = 41;
    private static final int MAX_STATE_BYTES = 32 * 1024;
    private static final long MAX_AUDIO_BYTES = 32L * 1024 * 1024;
    private static final Pattern HDMI_NUMBER = Pattern.compile("(?i)\\bHDMI[\\s_-]*(\\d+)\\b");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private final AtomicLong fileGeneration = new AtomicLong();
    private final Map<String, AppTarget> apps = new ConcurrentHashMap<>();
    private final Map<String, InputTarget> inputs = new ConcurrentHashMap<>();

    private SharedPreferences preferences;
    private UpdateManager updates;
    private FrameLayout content;
    private WebView web;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocus;
    private MediaPlayer player;
    private TextToSpeech speech;
    private boolean speechInitialized;
    private boolean speechReady;
    private SpeechRequest pendingSpeech;
    private String utteranceId;
    private volatile long audioGeneration;
    private int volume = 30;
    private volatile boolean resumed;
    private boolean pageReady;
    private boolean jsResumed;
    private boolean pickerOpen;
    private boolean suppressNextResume;
    private boolean suppressPageResume;
    private boolean backPending;
    private volatile boolean destroyed;
    private FrameLayout inputLayer;
    private TvView inputVideo;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        volume = clampVolume(preferences.getInt("volume", 30));
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        content = new FrameLayout(this);
        content.setBackgroundColor(Color.rgb(43, 8, 24));
        setContentView(content);
        updates = new UpdateManager(this, info -> emit("updateChanged", info), () -> {
            stopAudioInternal();
            suppressNextResume = true;
        });
        configureWebView();
        initializeSpeech();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        web.loadUrl(START_URL);
    }

    private void configureWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(43, 8, 24));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false);
        // Audio is owned by the native player, including focus and lifecycle handling.
        settings.setMediaPlaybackRequiresUserGesture(true);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.addJavascriptInterface(new NativeBridge(), "BayernNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (isLocalAsset(request.getUrl())) {
                    WebResourceResponse response = loader.shouldInterceptRequest(request.getUrl());
                    return response != null ? response : blockedResponse();
                }
                return blockedResponse();
            }

            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                if (isLocalAsset(request.getUrl())) return false;
                if (request.isForMainFrame()) openExternal(request.getUrl());
                return true;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isLocalAsset(uri)) return false;
                openExternal(uri);
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageReady = false;
                jsResumed = false;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!isLocalAsset(Uri.parse(url))) return;
                pageReady = true;
                view.requestFocus();
                resumeJavaScript();
            }
        });
    }

    private static boolean isLocalAsset(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && HOST.equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getPath() != null && uri.getPath().startsWith("/assets/");
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private void openExternal(Uri uri) {
        String scheme = uri == null ? "" : uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            showToast("Auf diesem Fernseher ist kein Browser verfügbar.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (web != null) web.onResume();
        if (suppressNextResume) {
            suppressNextResume = false;
            if (pageReady) {
                jsResumed = true;
                emit("resume", true);
            }
            else suppressPageResume = true;
            return;
        }
        resumeJavaScript();
    }

    private void resumeJavaScript() {
        if (!resumed || !pageReady || jsResumed || pickerOpen || inputLayer != null) return;
        jsResumed = true;
        if (suppressPageResume) {
            suppressPageResume = false;
            emit("resume", true);
            return;
        }
        emit("resume");
    }

    private void pauseJavaScript() {
        if (pageReady && jsResumed) emit("pause");
        jsResumed = false;
    }

    @Override protected void onPause() {
        resumed = false;
        pauseJavaScript();
        stopAudioInternal();
        closeInputView(false);
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        stopAudioInternal();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        fileGeneration.incrementAndGet();
        fileWorker.shutdownNow();
        if (updates != null) updates.close();
        stopAudioInternal();
        if (speech != null) speech.shutdown();
        if (web != null) {
            web.removeJavascriptInterface("BayernNative");
            web.stopLoading();
            content.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (inputLayer != null) {
            closeInputView(true);
            return;
        }
        if (backPending) return;
        if (!pageReady || web == null) { finish(); return; }
        backPending = true;
        web.evaluateJavascript("(function(){try{var e=window.BayernNativeEvents;"
                + "return !!(e&&typeof e.back==='function'&&e.back());}catch(_){return false;}})()",
                result -> {
                    backPending = false;
                    if (!destroyed && !"true".equals(result)) finish();
                });
    }

    private final class NativeBridge {
        @JavascriptInterface public String getApps() { return discoverApps(); }

        @JavascriptInterface public boolean launchApp(String id) {
            if (id == null || id.length() > 250) return false;
            if (!apps.containsKey(id)) discoverApps();
            AppTarget target = apps.get(id);
            if (target == null) return false;
            return onMain(() -> {
                stopAudioInternal();
                try {
                    startActivity(new Intent(target.intent)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                    return true;
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    reportError("Die App „" + target.name + "“ konnte nicht geöffnet werden.");
                    return false;
                }
            }, false);
        }

        @JavascriptInterface public String getInputs() { return discoverInputs(); }

        @JavascriptInterface public boolean openInput(String id) {
            if (id == null || id.length() > 1000) return false;
            discoverInputs();
            InputTarget target = inputs.get(id);
            if (target == null) return false;
            return onMain(() -> openTvInput(target), false);
        }

        @JavascriptInterface public boolean openInputSettings() {
            return onMain(MainActivity.this::openInputSettingsInternal, false);
        }

        @JavascriptInterface public String getState() {
            String json = preferences.getString(UI_STATE, "{}");
            try { return new JSONObject(json == null ? "{}" : json).toString(); }
            catch (JSONException invalid) { return "{}"; }
        }

        @JavascriptInterface public void saveState(String json) {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
                showToast("Die Einstellungen sind zu groß und wurden nicht gespeichert.");
                return;
            }
            try {
                String object = new JSONObject(json).toString();
                preferences.edit().putString(UI_STATE, object).apply();
            } catch (JSONException invalid) {
                showToast("Die Einstellungen konnten nicht gespeichert werden.");
            }
        }

        @JavascriptInterface public void playHymn() { main.post(MainActivity.this::playHymnInternal); }
        @JavascriptInterface public void stopAudio() {
            // Finish invalidating the previous run before JavaScript can start another one.
            onMain(() -> { stopAudioInternal(); return true; }, false);
        }
        @JavascriptInterface public boolean isAudioCurrent(long generation) {
            return generation == audioGeneration && resumed && !destroyed;
        }
        @JavascriptInterface public void setVolume(int value) {
            main.post(() -> {
                volume = clampVolume(value);
                preferences.edit().putInt("volume", volume).apply();
                if (player != null) {
                    try { player.setVolume(volume / 100f, volume / 100f); }
                    catch (IllegalStateException ignored) { }
                }
            });
        }
        @JavascriptInterface public void speak(String text, String style, int speechVolume) {
            main.post(() -> speakInternal(text, style, speechVolume));
        }
        @JavascriptInterface public void chooseAudio() { main.post(MainActivity.this::chooseAudioInternal); }
        @JavascriptInterface public void resetAudio() { main.post(MainActivity.this::resetAudioInternal); }
        @JavascriptInterface public String getAudioInfo() { return audioInfo().toString(); }
        @JavascriptInterface public String getUpdateInfo() { return updates.getInfoJSON().toString(); }
        @JavascriptInterface public boolean setUpdateSource(String source) { return onMain(() -> updates.setSource(source), false); }
        @JavascriptInterface public void checkUpdate() { updates.check(); }
        @JavascriptInterface public void downloadUpdate() { updates.download(); }
        @JavascriptInterface public void chooseUpdateApk() { main.post(() -> updates.chooseApk()); }
        @JavascriptInterface public void installUpdate() { updates.install(); }
        @JavascriptInterface public void openUpdateSettings() { main.post(() -> updates.openInstallSettings()); }
    }

    private static final class AppTarget {
        final String name;
        final Intent intent;
        final ResolveInfo info;
        AppTarget(String name, Intent intent, ResolveInfo info) {
            this.name = name; this.intent = intent; this.info = info;
        }
    }

    private String discoverApps() {
        if (destroyed) return "[]";
        PackageManager pm = getPackageManager();
        Map<String, AppTarget> found = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.exported
                        || !info.activityInfo.applicationInfo.enabled) continue;
                String id = info.activityInfo.packageName;
                if (getPackageName().equals(id) || found.containsKey(id)) continue;
                CharSequence label = info.loadLabel(pm);
                String name = label == null ? id : label.toString();
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(new ComponentName(id, info.activityInfo.name));
                found.put(id, new AppTarget(name, launch, info));
            }
        }
        apps.clear();
        apps.putAll(found);
        List<String> ids = new ArrayList<>(found.keySet());
        Collator order = Collator.getInstance(Locale.GERMANY);
        ids.sort((a, b) -> order.compare(found.get(a).name, found.get(b).name));
        JSONArray result = new JSONArray();
        for (String id : ids) {
            AppTarget app = found.get(id);
            try {
                JSONObject entry = new JSONObject().put("id", id).put("name", app.name).put("brand", app.name);
                String icon = iconData(app.info, pm);
                if (icon != null) entry.put("icon", icon);
                String banner = bannerData(app.info, pm);
                if (banner != null) entry.put("banner", banner).put("artworkKind", "banner");
                else if (icon != null) entry.put("artworkKind", "icon");
                result.put(entry);
            } catch (JSONException ignored) { }
        }
        return result.toString();
    }

    private static String bannerData(ResolveInfo info, PackageManager pm) {
        if (info.activityInfo == null) return null;
        try {
            String data = renderBannerData(info.activityInfo.loadBanner(pm));
            if (data != null) return data;
        } catch (RuntimeException unavailable) { }
        try {
            return renderBannerData(pm.getApplicationBanner(info.activityInfo.applicationInfo));
        } catch (RuntimeException unavailable) { return null; }
    }

    private static String renderBannerData(Drawable drawable) {
        if (drawable == null) return null;
        Bitmap bitmap = null;
        Rect oldBounds = new Rect(drawable.getBounds());
        try {
            // A small, offline 16:9 canvas keeps the bridge payload bounded. Fit rather
            // than stretch unusual artwork, preserving logos and text at the edges.
            final int width = 320, height = 180;
            int sourceWidth = drawable.getIntrinsicWidth();
            int sourceHeight = drawable.getIntrinsicHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                sourceWidth = width;
                sourceHeight = height;
            }
            float scale = Math.min(width / (float) sourceWidth, height / (float) sourceHeight);
            int fittedWidth = Math.max(1, Math.min(width, Math.round(sourceWidth * scale)));
            int fittedHeight = Math.max(1, Math.min(height, Math.round(sourceHeight * scale)));
            int left = (width - fittedWidth) / 2, top = (height - fittedHeight) / 2;
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            drawable.setBounds(left, top, left + fittedWidth, top + fittedHeight);
            drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null;
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (RuntimeException unavailable) { return null; }
        finally {
            drawable.setBounds(oldBounds);
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static String iconData(ResolveInfo info, PackageManager pm) {
        Bitmap bitmap = null;
        try {
            Drawable drawable = info.loadIcon(pm);
            if (drawable == null) return null;
            bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Rect old = new Rect(drawable.getBounds());
            drawable.setBounds(0, 0, 64, 64);
            drawable.draw(canvas);
            drawable.setBounds(old);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (RuntimeException ignored) { return null; }
        finally { if (bitmap != null) bitmap.recycle(); }
    }

    private static final class InputTarget {
        final String id, name, port;
        final int state;
        InputTarget(String id, String name, String port, int state) {
            this.id = id; this.name = name; this.port = port; this.state = state;
        }
    }

    private String discoverInputs() {
        JSONArray result = new JSONArray();
        Map<String, InputTarget> found = new HashMap<>();
        TvInputManager manager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (manager == null || destroyed) return "[]";
        try {
            List<TvInputInfo> list = manager.getTvInputList();
            Map<String, TvInputInfo> all = new HashMap<>();
            for (TvInputInfo info : list) all.put(info.getId(), info);
            for (TvInputInfo info : list) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI || info.isHidden(this)) continue;
                // CEC children name devices attached to a physical port; don't duplicate that port.
                if (info.getParentId() != null && all.containsKey(info.getParentId())) continue;
                String systemName = stringLabel(info.loadLabel(this), "HDMI");
                String name = stringLabel(info.loadCustomLabel(this), systemName);
                String port = hdmiPort(systemName);
                found.put(info.getId(), new InputTarget(info.getId(), name, port,
                        manager.getInputState(info.getId())));
            }
            inputs.clear(); inputs.putAll(found);
            List<InputTarget> sorted = new ArrayList<>(found.values());
            sorted.sort((a, b) -> a.port.compareToIgnoreCase(b.port));
            for (InputTarget input : sorted) result.put(new JSONObject()
                    .put("id", input.id).put("name", input.name).put("port", input.port)
                    .put("state", input.state).put("status", input.state == TvInputManager.INPUT_STATE_CONNECTED
                            ? "Verbunden" : input.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY
                            ? "Standby" : "Nicht verbunden"));
        } catch (RuntimeException | JSONException unavailable) {
            inputs.clear();
        }
        return result.toString();
    }

    private static String stringLabel(CharSequence label, String fallback) {
        return label == null || label.toString().trim().isEmpty() ? fallback : label.toString().trim();
    }

    private static String hdmiPort(String label) {
        Matcher matcher = HDMI_NUMBER.matcher(label);
        return matcher.find() ? "HDMI " + matcher.group(1) : "HDMI";
    }

    private boolean openTvInput(InputTarget target) {
        stopAudioInternal();
        Uri uri = TvContract.buildChannelUriForPassthroughInput(target.id);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, TvContract.Channels.CONTENT_ITEM_TYPE));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            // Some integrated Fire TVs expose a TvInputService without an exported input player.
            return showInputView(target);
        }
    }

    private boolean showInputView(InputTarget target) {
        closeInputView(false);
        pauseJavaScript();
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(Color.BLACK);
        TvView video = new TvView(this);
        layer.addView(video, new FrameLayout.LayoutParams(-1, -1));
        TextView status = new TextView(this);
        status.setText(target.name + " · Verbindung wird hergestellt …");
        status.setTextColor(Color.WHITE); status.setTextSize(18);
        status.setPadding(24, 18, 120, 18);
        status.setBackgroundColor(0x99000000);
        layer.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        Button back = new Button(this);
        back.setText("Zurück"); back.setOnClickListener(v -> closeInputView(true));
        FrameLayout.LayoutParams backLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        layer.addView(back, backLayout);
        inputLayer = layer; inputVideo = video;
        web.setVisibility(View.INVISIBLE);
        content.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        video.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String id) {
                if (inputVideo == video) status.setVisibility(View.GONE);
            }
            @Override public void onVideoUnavailable(String id, int reason) {
                if (inputVideo == video) {
                    status.setVisibility(View.VISIBLE);
                    status.setText(target.name + " · Kein Bild. Prüfe, ob das Gerät eingeschaltet ist.");
                }
            }
            @Override public void onConnectionFailed(String id) {
                if (inputVideo != video) return;
                closeInputView(false);
                showToast("Dieser TV gibt den Eingang nicht frei. Öffne ihn im TV-Eingangsmenü.");
                openInputSettingsInternal();
            }
        });
        try {
            video.tune(target.id, TvContract.buildChannelUriForPassthroughInput(target.id));
            video.setStreamVolume(1f);
            video.requestFocus();
            return true;
        } catch (RuntimeException unavailable) {
            closeInputView(false);
            return openInputSettingsInternal();
        }
    }

    private void closeInputView(boolean resumeHome) {
        TvView oldVideo = inputVideo;
        FrameLayout oldLayer = inputLayer;
        inputVideo = null; inputLayer = null;
        if (oldVideo != null) {
            oldVideo.setCallback(null);
            oldVideo.reset();
        }
        if (oldLayer != null) content.removeView(oldLayer);
        if (web != null) web.setVisibility(View.VISIBLE);
        if (resumeHome) resumeJavaScript();
    }

    private boolean openInputSettingsInternal() {
        stopAudioInternal();
        for (String action : new String[]{"android.settings.TV_INPUT_SETTINGS",
                "com.android.tv.action.VIEW_INPUTS", Settings.ACTION_SETTINGS}) {
            try { startActivity(new Intent(action)); return true; }
            catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        reportError("Das TV-Eingangsmenü ist nicht verfügbar. Nutze die Eingangstaste der Fernbedienung.");
        return false;
    }

    private boolean audioAllowed() {
        return resumed && !destroyed && !pickerOpen && inputLayer == null;
    }

    private AudioAttributes audioAttributes(boolean voice) {
        return new AudioAttributes.Builder()
                .setUsage(voice ? AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE : AudioAttributes.USAGE_MEDIA)
                .setContentType(voice ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private boolean requestFocus(long generation, AudioAttributes attributes) {
        if (audioManager == null) return false;
        AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (generation == audioGeneration && change < 0) {
                        stopAudioInternal();
                        emitAudio(audioGeneration, "audioError", "Die Wiedergabe wurde unterbrochen.");
                    }
                }, main).build();
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
        audioFocus = request;
        return true;
    }

    private void abandonFocus() {
        AudioFocusRequest request = audioFocus;
        audioFocus = null;
        if (request != null && audioManager != null) audioManager.abandonAudioFocusRequest(request);
    }

    private void stopAudioInternal() {
        audioGeneration++;
        pendingSpeech = null;
        utteranceId = null;
        releasePlayer();
        if (speech != null) {
            try { speech.stop(); } catch (RuntimeException ignored) { }
        }
        abandonFocus();
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        if (old == null) return;
        old.setOnPreparedListener(null); old.setOnCompletionListener(null); old.setOnErrorListener(null);
        try { old.reset(); } catch (RuntimeException ignored) { }
        old.release();
    }

    private void playHymnInternal() {
        stopAudioInternal();
        if (!audioAllowed()) return;
        long generation = audioGeneration;
        if (volume == 0) { failAudio(generation, "Die Lautstärke steht auf 0 Prozent."); return; }
        AudioAttributes attributes = audioAttributes(false);
        if (!requestFocus(generation, attributes)) {
            failAudio(generation, "Der Fernseher gibt die Audiowiedergabe gerade nicht frei."); return;
        }
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(attributes);
        next.setVolume(volume / 100f, volume / 100f);
        next.setOnPreparedListener(ready -> {
            if (generation != audioGeneration || player != ready || !audioAllowed()) return;
            try { ready.start(); }
            catch (IllegalStateException failed) { failAudio(generation, "Die Torhymne konnte nicht gestartet werden."); }
        });
        next.setOnCompletionListener(done -> {
            if (generation != audioGeneration || player != done) return;
            releasePlayer(); abandonFocus();
            emitAudio(generation, "hymnEnded");
        });
        next.setOnErrorListener((failed, what, extra) -> {
            if (generation == audioGeneration && player == failed) {
                failAudio(generation, "Diese Audiodatei kann auf diesem TV nicht abgespielt werden.");
            }
            return true;
        });
        try {
            File custom = customAudioFile();
            if (hasCustomAudio()) next.setDataSource(custom.getAbsolutePath());
            else {
                try (AssetFileDescriptor asset = getAssets().openFd(DEFAULT_AUDIO)) {
                    next.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
                } catch (IOException compressedAsset) {
                    File cached = new File(getCacheDir(), "bundled-hymn.m4a");
                    try (InputStream in = getAssets().open(DEFAULT_AUDIO);
                         FileOutputStream out = new FileOutputStream(cached)) {
                        byte[] buffer = new byte[8192]; int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                    next.setDataSource(cached.getAbsolutePath());
                }
            }
            next.prepareAsync();
        } catch (IOException | RuntimeException unavailable) {
            failAudio(generation, "Die Torhymne konnte nicht geladen werden. Wähle gegebenenfalls eine andere Datei.");
        }
    }

    private void initializeSpeech() {
        speech = new TextToSpeech(this, result -> main.post(() -> {
            if (destroyed || speech == null) return;
            speechInitialized = true;
            try {
                speechReady = result == TextToSpeech.SUCCESS
                        && speech.isLanguageAvailable(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE
                        && speech.setLanguage(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE;
            } catch (RuntimeException unavailable) { speechReady = false; }
            SpeechRequest waiting = pendingSpeech;
            pendingSpeech = null;
            if (waiting != null && waiting.generation == audioGeneration) {
                if (speechReady) startSpeech(waiting);
                else failAudio(waiting.generation, "Auf diesem TV ist keine deutsche Sprachausgabe verfügbar.");
            }
        }));
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                main.post(() -> {
                    if (isCurrentSpeech(id)) emitAudio(audioGeneration, "speechStarted");
                });
            }
            @Override public void onDone(String id) {
                main.post(() -> {
                    if (!isCurrentSpeech(id)) return;
                    long generation = audioGeneration;
                    utteranceId = null; abandonFocus();
                    emitAudio(generation, "speechEnded");
                });
            }
            @Override public void onError(String id) { speechError(id); }
            @Override public void onError(String id, int code) { speechError(id); }
        });
    }

    private boolean isCurrentSpeech(String id) {
        return id != null && id.equals(utteranceId) && id.equals("heimspiel-" + audioGeneration)
                && audioAllowed();
    }

    private void speechError(String id) {
        main.post(() -> {
            if (isCurrentSpeech(id)) failAudio(audioGeneration, "Die Begrüßung konnte nicht vorgelesen werden.");
        });
    }

    private static final class SpeechRequest {
        final String text, style;
        final int volume;
        final long generation;
        SpeechRequest(String text, String style, int volume, long generation) {
            this.text = text; this.style = style; this.volume = volume; this.generation = generation;
        }
    }

    private void speakInternal(String text, String style, int requestedVolume) {
        stopAudioInternal();
        if (!audioAllowed()) return;
        long generation = audioGeneration;
        if (text == null || text.trim().isEmpty()) {
            failAudio(generation, "Gib zuerst einen Begrüßungstext ein."); return;
        }
        int amount = clampVolume(requestedVolume);
        if (amount == 0) { failAudio(generation, "Die Lautstärke steht auf 0 Prozent."); return; }
        SpeechRequest request = new SpeechRequest(text.trim().substring(0, Math.min(text.trim().length(), 1000)),
                style == null ? "warm" : style, amount, generation);
        if (!speechInitialized) {
            pendingSpeech = request;
            main.postDelayed(() -> {
                if (pendingSpeech == request && request.generation == audioGeneration) {
                    failAudio(request.generation, "Die deutsche Stimme ist auf diesem TV noch nicht verfügbar.");
                }
            }, 5000);
        } else if (!speechReady) {
            failAudio(generation, "Auf diesem TV ist keine deutsche Sprachausgabe verfügbar.");
        } else startSpeech(request);
    }

    private void startSpeech(SpeechRequest request) {
        if (request.generation != audioGeneration || !audioAllowed()) return;
        AudioAttributes attributes = audioAttributes(true);
        if (!requestFocus(request.generation, attributes)) {
            failAudio(request.generation, "Der Fernseher gibt die Sprachausgabe gerade nicht frei."); return;
        }
        try {
            speech.setAudioAttributes(attributes);
            speech.setSpeechRate("calm".equals(request.style) ? 0.86f : "lively".equals(request.style) ? 1.08f : 0.98f);
            speech.setPitch("calm".equals(request.style) ? 0.93f : "lively".equals(request.style) ? 1.04f : 1f);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, request.volume / 100f);
            utteranceId = "heimspiel-" + request.generation;
            if (speech.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId) == TextToSpeech.ERROR) {
                failAudio(request.generation, "Die Begrüßung konnte nicht vorgelesen werden.");
            }
        } catch (RuntimeException unavailable) {
            failAudio(request.generation, "Die deutsche Sprachausgabe ist auf diesem TV nicht verfügbar.");
        }
    }

    private void failAudio(long generation, String message) {
        if (generation != audioGeneration) return;
        stopAudioInternal();
        emitAudio(audioGeneration, "audioError", message);
    }

    private File customAudioFile() { return new File(getFilesDir(), "custom-hymn.audio"); }
    private boolean hasCustomAudio() {
        return customAudioFile().isFile() && !preferences.getString(CUSTOM_AUDIO_NAME, "").isEmpty();
    }

    private JSONObject audioInfo() {
        JSONObject info = new JSONObject();
        try {
            boolean custom = hasCustomAudio();
            info.put("custom", custom).put("name", custom
                    ? preferences.getString(CUSTOM_AUDIO_NAME, "Eigene Torhymne")
                    : "Bayern-Torhymne · 0–5 Sekunden");
        } catch (JSONException ignored) { }
        return info;
    }

    private void chooseAudioInternal() {
        if (destroyed || pickerOpen) return;
        stopAudioInternal();
        Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pickerOpen = true;
        suppressNextResume = true;
        try { startActivityForResult(choose, PICK_AUDIO); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            pickerOpen = false; suppressNextResume = false;
            reportError("Auf diesem TV ist keine Dateiauswahl verfügbar. Die integrierte Torhymne bleibt nutzbar.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (updates != null && updates.onActivityResult(request, result, data)) return;
        if (request != PICK_AUDIO) return;
        pickerOpen = false;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        long generation = fileGeneration.incrementAndGet();
        fileWorker.execute(() -> copyChosenAudio(uri, generation));
    }

    private void copyChosenAudio(Uri uri, long generation) {
        File temporary = null;
        try {
            String name = "Eigene Torhymne";
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
            if (name == null || name.trim().isEmpty()) name = "Eigene Torhymne";
            name = name.replaceAll("[\\p{Cntrl}]", "");
            name = name.substring(0, Math.min(name.length(), 120));
            temporary = File.createTempFile("heimspiel-audio-", ".tmp", getCacheDir());
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new IOException("Die Datei ist nicht lesbar.");
                byte[] buffer = new byte[16384]; int read; long total = 0;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Abgebrochen");
                    total += read;
                    if (total > MAX_AUDIO_BYTES) throw new IOException("Bitte wähle eine Audiodatei mit höchstens 32 MB.");
                    out.write(buffer, 0, read);
                }
                if (total == 0) throw new IOException("Die Audiodatei ist leer.");
            }
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(temporary.getAbsolutePath());
                String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (duration == null || Long.parseLong(duration) <= 0) throw new IOException("Kein abspielbares Audio gefunden.");
            } finally { metadata.release(); }
            final File ready = temporary;
            final String chosenName = name;
            main.post(() -> {
                if (destroyed || generation != fileGeneration.get()) { ready.delete(); return; }
                stopAudioInternal();
                try {
                    try {
                        Files.move(ready.toPath(), customAudioFile().toPath(),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException fallback) {
                        Files.move(ready.toPath(), customAudioFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    preferences.edit().putString(CUSTOM_AUDIO_NAME, chosenName).apply();
                    emit("audioChanged", audioInfo());
                } catch (IOException failed) {
                    ready.delete(); reportError("Die Audiodatei konnte nicht gespeichert werden.");
                }
            });
        } catch (Exception unavailable) {
            if (temporary != null) temporary.delete();
            main.post(() -> {
                if (destroyed || generation != fileGeneration.get()) return;
                String message = unavailable instanceof IOException ? unavailable.getMessage() : null;
                reportError(message != null && !message.isEmpty() ? message
                        : "Diese Audiodatei kann auf diesem TV nicht geöffnet werden.");
            });
        }
    }

    private void resetAudioInternal() {
        fileGeneration.incrementAndGet();
        stopAudioInternal();
        preferences.edit().remove(CUSTOM_AUDIO_NAME).apply();
        customAudioFile().delete();
        emit("audioChanged", audioInfo());
    }

    private static int clampVolume(int amount) { return Math.max(0, Math.min(100, amount)); }

    private void showToast(String message) {
        main.post(() -> { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); });
    }

    private void reportError(String message) {
        showToast(message);
        emit("audioError", message);
    }

    private void emitAudio(long generation, String event, Object... args) {
        main.post(() -> {
            if (generation != audioGeneration || !audioAllowed()
                    || !pageReady || web == null) return;
            // evaluateJavascript is asynchronous: recheck in the renderer so a queued end
            // event cannot complete a newer run started between native and JS execution.
            web.evaluateJavascript(eventScript(event, args,
                    "if(!window.BayernNative||!window.BayernNative.isAudioCurrent("
                            + generation + "))return;"), null);
        });
    }

    private void emit(String event, Object... args) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> emit(event, args));
            return;
        }
        if (destroyed || !pageReady || web == null) return;
        web.evaluateJavascript(eventScript(event, args, ""), null);
    }

    private String eventScript(String event, Object[] args, String guard) {
        StringBuilder arguments = new StringBuilder();
        for (Object arg : args) {
            if (arguments.length() > 0) arguments.append(',');
            if (arg instanceof JSONObject || arg instanceof JSONArray) arguments.append(arg);
            else if (arg instanceof Boolean || arg instanceof Number) arguments.append(arg);
            else if (arg == null) arguments.append("null");
            else arguments.append(JSONObject.quote(String.valueOf(arg)));
        }
        return "(function(){" + guard + "var e=window.BayernNativeEvents;if(e&&typeof e."
                + event + "==='function')e." + event + "(" + arguments + ");})()";
    }

    private <T> T onMain(Callable<T> work, T fallback) {
        if (destroyed) return fallback;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return work.call(); } catch (Exception unavailable) { return fallback; }
        }
        FutureTask<T> task = new FutureTask<>(() -> destroyed ? fallback : work.call());
        main.post(task);
        try { return task.get(3, TimeUnit.SECONDS); }
        catch (Exception unavailable) { task.cancel(false); return fallback; }
    }
}
