package com.kamisakyy.cutpro;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/** Publishes finished videos into the Gallery (Movies/CutPro). */
public final class MediaSaver {

    public static final String ALBUM = "CutPro";

    private MediaSaver() {
    }

    public static Uri saveVideo(Context context, File source, String displayName) {
        if (source == null || !source.exists() || source.length() == 0) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/" + ALBUM);
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                resolver.delete(uri, null, null);
                return null;
            }
            FileInputStream in = new FileInputStream(source);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();

            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        } catch (Throwable t) {
            try {
                if (uri != null) {
                    resolver.delete(uri, null, null);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
