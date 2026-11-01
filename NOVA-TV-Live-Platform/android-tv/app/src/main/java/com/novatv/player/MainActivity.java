package com.novatv.player;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enableImmersiveMode();

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
                evaluateJs("window.novaNativePlayerState && window.novaNativePlayerState(" + playing + ")");
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                playerView.setVisibility(View.GONE);
                fullscreen = false;
                evaluateJs("window.novaNativeError && window.novaNativeError('تعذّر تشغيل الرابط. تحقق من صلاحية البث أو صيغة الفيديو.')");
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
            player.stop();
            player.clearMediaItems();
            player.setMediaItem(builder.build());
            player.prepare();
            player.play();
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
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
        @JavascriptInterface
        public void play(String url) { runOnUiThread(() -> playMedia(url)); }

        @JavascriptInterface
        public void stop() { runOnUiThread(() -> stopMedia()); }

        @JavascriptInterface
        public void toggle() {
            runOnUiThread(() -> {
                if (player.getPlayWhenReady()) player.pause(); else player.play();
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
