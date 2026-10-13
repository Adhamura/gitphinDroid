package io.github.adhamura.gitphindroid.ui.share;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Reads what another app shared (Uris or text) into a list of items to save. */
final class ShareItems {
    static final long MAX_BYTES = 32L * 1024 * 1024;

    /** One shared file. data is set for text shares; otherwise the bytes are read from uri on save. */
    static final class Item {
        String name;
        long size = -1;
        Uri uri;
        byte[] data;
        boolean tooLarge;
    }

    final List<Item> items = new ArrayList<>();
    final List<Item> usable = new ArrayList<>();

    ShareItems(Context ctx, Intent in) {
        readItems(ctx, in);
    }

    @SuppressWarnings("deprecation")
    private static Uri streamOf(Intent in) {
        Object o = in.getParcelableExtra(Intent.EXTRA_STREAM);
        return o instanceof Uri ? (Uri) o : null;
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> streamsOf(Intent in) {
        List<Uri> out = new ArrayList<>();
        ArrayList<android.os.Parcelable> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (l != null) {
            for (android.os.Parcelable p : l) if (p instanceof Uri) out.add((Uri) p);
        }
        return out;
    }

    private void readItems(Context ctx, Intent in) {
        List<Uri> uris = new ArrayList<>();
        if (in != null) {
            Uri one = streamOf(in);
            if (one != null) uris.add(one);
            uris.addAll(streamsOf(in));
            if (uris.isEmpty()) {
                ClipData cd = in.getClipData();
                if (cd != null) {
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        Uri u = cd.getItemAt(i).getUri();
                        if (u != null) uris.add(u);
                    }
                }
            }
        }
        for (Uri u : uris) items.add(describe(ctx, u));
        if (items.isEmpty() && in != null) {
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            CharSequence subj = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            if (text != null && text.length() > 0) {
                boolean md = (subj != null && subj.length() > 0) || "text/markdown".equals(in.getType());
                String body = text.toString();
                if (md && subj != null && subj.length() > 0) body = "# " + subj + "\n\n" + body;
                Item it = new Item();
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                it.name = "note-" + stamp + (md ? ".md" : ".txt");
                it.data = body.getBytes(StandardCharsets.UTF_8);
                it.size = it.data.length;
                items.add(it);
            }
        }
        for (Item it : items) {
            it.tooLarge = it.size > MAX_BYTES;
            if (!it.tooLarge) usable.add(it);
        }
    }

    private static Item describe(Context ctx, Uri u) {
        Item it = new Item();
        it.uri = u;
        String name = null;
        try (Cursor c = ctx.getContentResolver().query(u,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) it.size = c.getLong(si);
            }
        } catch (RuntimeException ignored) {
            // no metadata: use the fallbacks below
        }
        if (name == null || name.trim().isEmpty()) name = u.getLastPathSegment();
        it.name = cleanName(name);
        return it;
    }

    private static String cleanName(String n) {
        if (n == null) return "shared.bin";
        n = n.replace('/', '_').replace('\\', '_').trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return "shared.bin";
        return n;
    }

    static String human(long n) {
        if (n < 0) return "? B";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    static byte[] bytesOf(ContentResolver cr, Item it) throws Exception {
        if (it.data != null) return it.data;
        try (InputStream in = cr.openInputStream(it.uri)) {
            if (in == null) throw new Exception("cannot open the file");
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(1024, Math.min(it.size, MAX_BYTES)));
            byte[] buf = new byte[65536];
            long sum = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                sum += r;
                if (sum > MAX_BYTES) throw new Exception("too large (max 32 MB)");
                bo.write(buf, 0, r);
            }
            return bo.toByteArray();
        }
    }
}
