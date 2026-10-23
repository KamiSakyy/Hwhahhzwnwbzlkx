package com.kamisakyy.webrecorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies finished recordings into the gallery or into a folder chosen by the user. */
public final class RecorderStore {

    public static final String ALBUM = "WebRecorder";

    private RecorderStore() {
    }

    /**
     * Saves the file into Movies/WebRecorder so it is indexed by the gallery.
     *
     * @return content uri of the saved video or null on failure
     */
    public static Uri saveToGallery(Context ctx, File src, String displayName) {
        if (src == null || !src.exists() || src.length() == 0) {
            return null;
        }
        ContentResolver cr = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ALBUM);
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        ALBUM);
                if (!dir.exists() && !dir.mkdirs()) {
                    return null;
                }
                File dest = new File(dir, displayName);
                if (!copyFile(src, dest)) {
                    return null;
                }
                values.put(MediaStore.Video.Media.DATA, dest.getAbsolutePath());
            }

            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutputStream os = null;
                try {
                    os = cr.openOutputStream(uri);
                    if (os == null) {
                        cr.delete(uri, null, null);
                        return null;
                    }
                    if (!copyStream(new FileInputStream(src), os)) {
                        cr.delete(uri, null, null);
                        return null;
                    }
                } finally {
                    closeQuietly(os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            }
            return uri;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Saves the file into a directory picked with ACTION_OPEN_DOCUMENT_TREE.
     */
    public static Uri saveToTree(Context ctx, File src, Uri treeUri, String displayName) {
        if (src == null || !src.exists() || treeUri == null) {
            return null;
        }
        ContentResolver cr = ctx.getContentResolver();
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
            Uri doc = DocumentsContract.createDocument(cr, parent, "video/mp4", displayName);
            if (doc == null) {
                return null;
            }
            OutputStream os = null;
            try {
                os = cr.openOutputStream(doc, "w");
                if (os == null) {
                    return doc;
                }
                copyStream(new FileInputStream(src), os);
            } finally {
                closeQuietly(os);
            }
            return doc;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean copyFile(File src, File dest) {
        try {
            return copyStream(new FileInputStream(src), new java.io.FileOutputStream(dest));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean copyStream(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
