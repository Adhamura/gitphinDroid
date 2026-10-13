package shots;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** content://shots.files/<name>: shared files with a name and size, as a gallery or file app sends them. */
public class DemoFiles extends ContentProvider {
    static File dir;

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String order) {
        File f = new File(dir, u.getLastPathSegment());
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {f.getName(), f.length()});
        return c;
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(new File(dir, u.getLastPathSegment()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri u) { return u.getLastPathSegment().endsWith(".jpg") ? "image/jpeg" : "text/markdown"; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
