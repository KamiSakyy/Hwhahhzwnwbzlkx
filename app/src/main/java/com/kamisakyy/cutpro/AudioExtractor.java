package com.kamisakyy.cutpro;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.util.Locale;

/** Pulls the audio track out of a video file into a standalone M4A ("video to audio"). */
public final class AudioExtractor {

    public interface Callback {
        void onDone(File file);

        void onError(String message);
    }

    private static Transformer active;

    private AudioExtractor() {
    }

    public static void extract(Context context, String videoUri, File outputFile, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        try {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            MediaItem item = new MediaItem.Builder()
                    .setUri(Uri.parse(videoUri))
                    .build();
            EditedMediaItem edited = new EditedMediaItem.Builder(item)
                    .setRemoveVideo(true)
                    .build();
            final Transformer transformer = new Transformer.Builder(context.getApplicationContext())
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult exportResult) {
                            active = null;
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onDone(outputFile);
                                }
                            });
                        }

                        @Override
                        public void onError(Composition composition, ExportResult exportResult,
                                            ExportException exportException) {
                            active = null;
                            final String message = describe(exportException);
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onError(message);
                                }
                            });
                        }
                    })
                    .build();
            active = transformer;
            transformer.start(edited, outputFile.getAbsolutePath());
        } catch (Throwable t) {
            callback.onError(describe(t));
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "Не удалось извлечь звук";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        return String.format(Locale.US, "%s", message);
    }
}
