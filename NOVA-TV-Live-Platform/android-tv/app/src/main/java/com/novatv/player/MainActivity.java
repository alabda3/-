package com.novatv.player;

import android.app.Activity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.webkit.WebViewAssetLoader;

/**
 * NOVA TV: Local HTML interface + native Media3 player.
 * All UI assets are served from the trusted appassets origin.
 * User-supplied media URLs are accepted only if they are http(s).
 */
public class MainActivity extends Activity {
    private static final String ASSETS_ORIGIN = "https://appassets.androidplatform.net/assets/";
    private static final int FILE_PICK_REQUEST = 501;

    private FrameLayout root;
    private WebView webView;
    private PlayerView playerView;
    private ExoPlayer player;
    private ValueCallback<Uri[]> pendingFileCallback;
    private volatile boolean editingText = false;
    private boolean fullscreen = false;
    private int frameLeft, frameTop, frameWidth = 1, frameHeight = 1;


    // NOVA_AUTO_RECOVERY_V1: restart HLS after transient failures, but never after Stop.
    private final Handler recoveryHandler = new Handler(Looper.getMainLooper());
    private MediaItem activeMediaItem;
    private boolean streamRequested = false;
    private boolean activityForeground = false;
    private boolean pausedByUser = false;
    private boolean retryScheduled = false;
    private int recoveryAttempts = 0;
    private long bufferingSinceMs = 0L;

    private final Runnable retryPlayback = new Runnable() {
        @Override public void run() {
            retryScheduled = false;
            if (!streamRequested || !activityForeground || pausedByUser || player == null || activeMediaItem == null) return;
            try {
                player.stop();
                player.clearMediaItems();
                player.setMediaItem(activeMediaItem);
                player.prepare();
                player.play();
                watchBuffering();
            } catch (RuntimeException ex) {
                Log.w("NOVA-TV", "Playback recovery failed", ex);
                scheduleRecovery();
            }
        }
    };

    private final Runnable bufferingWatchdog = new Runnable() {
        @Override public void run() {
            if (!streamRequested || !activityForeground || pausedByUser || player == null || retryScheduled) return;
            int state = player.getPlaybackState();
            long now = SystemClock.elapsedRealtime();
            if (state == Player.STATE_BUFFERING) {
                if (bufferingSinceMs == 0L) bufferingSinceMs = now;
                if (now - bufferingSinceMs > 25000L) {
                    Log.w("NOVA-TV", "HLS buffering stalled; reconnecting");
                    scheduleRecovery();
                    return;
                }
            } else if (state == Player.STATE_READY) {
                bufferingSinceMs = 0L;
            } else if (state == Player.STATE_ENDED) {
                scheduleRecovery();
                return;
            }
            recoveryHandler.postDelayed(this, 5000L);
        }
    };

    private void watchBuffering() {
        recoveryHandler.removeCallbacks(bufferingWatchdog);
        recoveryHandler.postDelayed(bufferingWatchdog, 5000L);
    }

    private void scheduleRecovery() {
        if (!streamRequested || !activityForeground || pausedByUser || player == null || retryScheduled) return;
        retryScheduled = true;
        recoveryHandler.removeCallbacks(bufferingWatchdog);
        // 2, 4, 8, 16, 30, 30... seconds. Never hammer a disconnected server.
        long delay = Math.min(30000L, 2000L * (1L << Math.min(recoveryAttempts, 4)));
        recoveryAttempts++;
        Log.w("NOVA-TV", "HLS reconnect attempt " + recoveryAttempts + " in " + delay + "ms");
        evaluateJs("window.novaNativeReconnecting && window.novaNativeReconnecting(" + (delay / 1000) + ")");
        recoveryHandler.postDelayed(retryPlayback, delay);
    }

    private void cancelRecovery() {
        recoveryHandler.removeCallbacks(retryPlayback);
        recoveryHandler.removeCallbacks(bufferingWatchdog);
        retryScheduled = false;
        bufferingSinceMs = 0L;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getPackageManager().hasSystemFeature("android.software.leanback")) {
            enableImmersiveMode();
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Android document picker returns content:// URIs.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                return !target.startsWith(ASSETS_ORIGIN);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = callback;
                Intent pickFile = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pickFile.addCategory(Intent.CATEGORY_OPENABLE);
                pickFile.setType("*/*");
                try {
                    startActivityForResult(pickFile, FILE_PICK_REQUEST);
                } catch (ActivityNotFoundException e) {
                    pendingFileCallback.onReceiveValue(null);
                    pendingFileCallback = null;
                    Toast.makeText(MainActivity.this,
                        "ثبت مدير ملفات على التلفزيون حتى تستورد M3U", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setFocusable(false);
        playerView.setClickable(false);
        playerView.setVisibility(View.GONE);
        root.addView(playerView, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));

        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) {
                    recoveryAttempts = 0;
                    bufferingSinceMs = 0L;
                    watchBuffering();
                }
                evaluateJs("window.novaNativePlayerState && window.novaNativePlayerState(" + playing + ")");
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING && bufferingSinceMs == 0L)
                    bufferingSinceMs = SystemClock.elapsedRealtime();
                if (state == Player.STATE_READY) bufferingSinceMs = 0L;
                if (state == Player.STATE_ENDED) scheduleRecovery();
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w("NOVA-TV", "Playback error code " + error.errorCode, error);
                // Keep the video area visible; retry instead of stopping the channel.
                scheduleRecovery();
            }
        });

        // Only app-owned HTML is navigable in this WebView.
        webView.addJavascriptInterface(new NativeBridge(), "NovaAndroid");
        webView.loadUrl(ASSETS_ORIGIN + "index.html");
        webView.requestFocus();
    }

    private void enableImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void evaluateJs(String source) {
        if (webView != null) webView.post(() -> {
            if (webView != null) webView.evaluateJavascript(source, null);
        });
    }

    private boolean validHttpUrl(String source) {
        if (source == null || source.length() > 8192) return false;
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
            && uri.getHost() != null && !uri.getHost().isEmpty();
    }

    private void playMedia(String source) {
        if (!validHttpUrl(source)) {
            evaluateJs("window.novaNativeError && window.novaNativeError('رابط غير صالح')");
            return;
        }
        Uri uri = Uri.parse(source);
        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
        String path = uri.getPath();
        if (path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        fullscreen = false;
        positionPlayer();
        playerView.setVisibility(View.VISIBLE);
        playerView.bringToFront();
        try {
            cancelRecovery();
            streamRequested = false;
            player.stop();
            player.clearMediaItems();
            activeMediaItem = builder.build();
            pausedByUser = false;
            recoveryAttempts = 0;
            streamRequested = true;
            player.setMediaItem(activeMediaItem);
            player.prepare();
            player.play();
            watchBuffering();
        } catch (RuntimeException e) {
            playerView.setVisibility(View.GONE);
            evaluateJs("window.novaNativeError && window.novaNativeError('تعذّر تهيئة مشغّل الفيديو')");
        }
    }

    private void positionPlayer() {
        FrameLayout.LayoutParams lp;
        if (fullscreen) {
            lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.LEFT);
        } else {
            lp = new FrameLayout.LayoutParams(Math.max(1, frameWidth), Math.max(1, frameHeight),
                Gravity.TOP | Gravity.LEFT);
            lp.leftMargin = frameLeft;
            lp.topMargin = frameTop;
        }
        playerView.setLayoutParams(lp);
        playerView.bringToFront();
    }

    private void stopMedia() {
        streamRequested = false;
        pausedByUser = false;
        activeMediaItem = null;
        cancelRecovery();
        fullscreen = false;
        player.stop();
        player.clearMediaItems();
        playerView.setVisibility(View.GONE);
        evaluateJs("window.novaNativePlayerState && window.novaNativePlayerState(false)");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!editingText) {
            String key = null;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: key = "ArrowLeft"; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: key = "ArrowRight"; break;
                case KeyEvent.KEYCODE_DPAD_UP: key = "ArrowUp"; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: key = "ArrowDown"; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: key = "Enter"; break;
            }
            if (key != null) {
                evaluateJs("window.novaTvKey && window.novaTvKey('" + key + "')");
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (fullscreen) {
            fullscreen = false;
            positionPlayer();
            return;
        }
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript("Boolean(window.novaTvBack && window.novaTvBack())", result -> {
            if ("false".equals(result)) finish();
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICK_REQUEST && pendingFileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) result = new Uri[]{ data.getData() };
            }
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
        }
    }

    @Override
    protected void onPause() {
        activityForeground = false;
        cancelRecovery();
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityForeground = true;
        if (player != null && streamRequested && !pausedByUser) {
            if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE) {
                recoveryHandler.post(retryPlayback);
            } else {
                player.play();
                watchBuffering();
            }
        }
    }

    @Override
    protected void onDestroy() {
        streamRequested = false;
        cancelRecovery();
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("NovaAndroid");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class NativeBridge {
        // Fetch only the public config. Background thread avoids blocking video/UI.
        @JavascriptInterface
        public void fetchChannelConfig(String origin) {
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    Uri uri = Uri.parse(origin == null ? "" : origin.trim());
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                            || uri.getHost().isEmpty() || uri.getUserInfo() != null) {
                        throw new IllegalArgumentException("Invalid HTTPS platform URL");
                    }
                    URL endpoint = new URL("https", uri.getHost(), uri.getPort(), "/config.json");
                    connection = (HttpURLConnection) endpoint.openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    connection.setUseCaches(false);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Accept", "application/json");
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                        throw new IllegalStateException("Config HTTP " + connection.getResponseCode());
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (bytes.size() + n > 131072) throw new IllegalStateException("Config too large");
                            bytes.write(buffer, 0, n);
                        }
                    }
                    String json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    new JSONObject(json); // Validate JSON before forwarding to UI.
                    evaluateJs("window.novaConfigLoaded && window.novaConfigLoaded(" + JSONObject.quote(json) + ")");
                } catch (Exception ex) {
                    android.util.Log.w("NOVA-TV", "Channel sync failed: " + ex.getMessage());
                    evaluateJs("window.novaConfigFailed && window.novaConfigFailed()");
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "nova-channels-sync").start();
        }


        @JavascriptInterface
        public void play(String url) { runOnUiThread(() -> playMedia(url)); }

        @JavascriptInterface
        public void stop() { runOnUiThread(() -> stopMedia()); }

        @JavascriptInterface
        public void toggle() {
            runOnUiThread(() -> {
                if (player.getPlayWhenReady()) {
                    pausedByUser = true;
                    cancelRecovery();
                    player.pause();
                } else {
                    pausedByUser = false;
                    if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE
                            || player.getPlaybackState() == Player.STATE_ENDED) {
                        recoveryHandler.post(retryPlayback);
                    } else {
                        player.play();
                        watchBuffering();
                    }
                }
            });
        }

        @JavascriptInterface
        public void fullscreen() {
            runOnUiThread(() -> {
                if (playerView.getVisibility() != View.VISIBLE) return;
                fullscreen = !fullscreen;
                positionPlayer();
            });
        }

        @JavascriptInterface
        public void setEditing(boolean value) { editingText = value; }

        @JavascriptInterface
        public void setBounds(float x, float y, float width, float height,
                              float viewportWidth, float viewportHeight) {
            runOnUiThread(() -> {
                if (viewportWidth < 1 || viewportHeight < 1 || width < 1 || height < 1) return;
                float sx = webView.getWidth() / viewportWidth;
                float sy = webView.getHeight() / viewportHeight;
                frameLeft = webView.getLeft() + Math.round(x * sx);
                frameTop = webView.getTop() + Math.round(y * sy);
                frameWidth = Math.round(width * sx);
                frameHeight = Math.round(height * sy);
                if (!fullscreen && playerView.getVisibility() == View.VISIBLE) positionPlayer();
            });
        }
    }
}
