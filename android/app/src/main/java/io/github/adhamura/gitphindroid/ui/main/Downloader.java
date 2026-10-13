package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.URLUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Saves a file that the web page downloads into the Downloads folder. */
final class Downloader {
    static final int REQ_STORAGE = 3;

    private final Activity a;
    private String[] pending; // url, contentDisposition, mime (waiting for storage permission)

    Downloader(Activity a) { this.a = a; }

    void start(String url, String cd, String mime) {
        if (Build.VERSION.SDK_INT < 29
                && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pending = new String[] {url, cd, mime};
            a.requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        final String name = URLUtil.guessFileName(url, cd, mime);
        final String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(120000);
                if (c.getResponseCode() / 100 != 2) throw new java.io.IOException("HTTP " + c.getResponseCode());
                String saved;
                try (InputStream in = c.getInputStream()) {
                    saved = save(name, type, in);
                } finally {
                    c.disconnect();
                }
                PixelToast.show(a, "Saved to Downloads: " + saved);
            } catch (Exception e) {
                PixelToast.show(a, "Download failed: " + e.getMessage());
            }
        }, "download").start();
    }

    void onPermissionResult(int[] res) {
        String[] p = pending;
        pending = null;
        if (p != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            start(p[0], p[1], p[2]);
        } else if (p != null) {
            PixelToast.show(a, "Storage permission denied");
        }
    }

    private String save(String name, String type, InputStream in) throws java.io.IOException {
        byte[] buf = new byte[32768];
        int n;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, type);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new java.io.IOException("cannot create file");
            try (OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (java.io.IOException e) {
                a.getContentResolver().delete(uri, null, null);
                throw e;
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            a.getContentResolver().update(uri, cv, null, null);
            return name;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        dir.mkdirs();
        File f = new File(dir, name);
        for (int i = 1; f.exists(); i++) f = new File(dir, i + "_" + name);
        try (OutputStream out = new FileOutputStream(f)) {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return f.getName();
    }
}
