package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.UiScale;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Settings and clients of the main WebView: local links stay, other links open outside,
 *  file chooser, downloads, text zoom of the UI size. */
final class WebViewSetup {
    static final int REQ_FILE = 1;

    private final Activity a;
    private final Downloader downloader;
    private final Runnable onMainFrameError, onPageFinished;
    private ValueCallback<Uri[]> fileCallback;

    WebViewSetup(Activity a, WebView web, Downloader downloader, Runnable onMainFrameError,
                 Runnable onPageFinished) {
        this.a = a;
        this.downloader = downloader;
        this.onMainFrameError = onMainFrameError;
        this.onPageFinished = onPageFinished;
        configure(web);
    }

    static boolean isLocal(Uri u) {
        String h = u.getHost();
        return "127.0.0.1".equals(h) || "localhost".equals(h);
    }

    @SuppressWarnings({"deprecation", "SetJavaScriptEnabled"})
    private void configure(WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(UiScale.large(a) ? 125 : 100);
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 33) {
            // The page has its own dark theme (prefers-color-scheme); do not force-invert it.
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (isLocal(u)) return false;
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (RuntimeException e) {
                    PixelToast.show(a, "No app to open " + u);
                }
                return true;
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) onMainFrameError.run();
            }

            @Override public void onPageFinished(WebView v, String url) {
                onPageFinished.run();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                                       FileChooserParams params) {
                return chooseFile(cb);
            }
        });

        web.setDownloadListener((DownloadListener) (url, ua, cd, mime, len) -> {
            Uri u = Uri.parse(url);
            if (!isLocal(u)) {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (RuntimeException ignored) { }
                return;
            }
            downloader.start(url, cd, mime);
        });
    }

    private boolean chooseFile(ValueCallback<Uri[]> cb) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            a.startActivityForResult(i, REQ_FILE);
        } catch (RuntimeException e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                a.startActivityForResult(g, REQ_FILE);
            } catch (RuntimeException e2) {
                fileCallback = null;
                cb.onReceiveValue(null);
                return false;
            }
        }
        return true;
    }

    /** Returns true when the result was the file chooser's. */
    boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_FILE) return false;
        if (fileCallback != null) {
            Uri[] r = res == Activity.RESULT_OK ? WebChromeClient.FileChooserParams.parseResult(res, data) : null;
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        }
        return true;
    }
}
