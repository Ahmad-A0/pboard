package dev.pboard.webfield;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Serves assets/fields.html as content://dev.pboard.webfield/fields.html, so Chrome can open the same page. */
public class PageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            return getContext().getAssets().openFd("fields.html");
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    @Override public String getType(Uri uri) { return "text/html"; }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {"fields.html", null});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
