package com.kamisakyy.cutpro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Pair;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders the whole project into an MP4 using Media3 Transformer. */
public final class VideoExporter {

    public interface Callback {
        void onProgress(int percent);

        void onDone(File file, int width, int height, long durationMs);

        void onError(String message);
    }

    /** A text overlay bound to the project timeline. */
    private static final class PlacedText {
        final Bitmap bitmap;
        final OverlaySettings visible;
        final OverlaySettings hidden;
        final long startUs;
        final long endUs;

        PlacedText(Bitmap bitmap, OverlaySettings visible, OverlaySettings hidden,
                   long startUs, long endUs) {
            this.bitmap = bitmap;
            this.visible = visible;
            this.hidden = hidden;
            this.startUs = startUs;
            this.endUs = endUs;
        }
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private Runnable poller;
    private boolean cancelled;

    public VideoExporter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void export(Project project, int shortSide, File output, final Callback callback) {
        final int[] size;
        final Composition composition;
        try {
            size = outputSize(project.aspect, shortSide);
            composition = buildComposition(project, size[0], size[1]);
        } catch (Throwable t) {
            callback.onError(describe(t));
            return;
        }
        final int width = size[0];
        final int height = size[1];

        int bitrate = shortSide >= 1080 ? 12_000_000 : 7_000_000;
        VideoEncoderSettings encoderSettings = new VideoEncoderSettings.Builder()
                .setBitrate(bitrate)
                .build();
        DefaultEncoderFactory encoderFactory = new DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .build();

        try {
            transformer = new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult exportResult) {
                            stopPolling();
                            final File file = output;
                            final int outW = exportResult.width > 0 ? exportResult.width : width;
                            final int outH = exportResult.height > 0 ? exportResult.height : height;
                            final long duration = exportResult.durationMs;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!cancelled) {
                                        callback.onDone(file, outW, outH, duration);
                                    }
                                }
                            });
                        }

                        @Override
                        public void onError(Composition composition, ExportResult exportResult,
                                            ExportException exportException) {
                            stopPolling();
                            final String message = describe(exportException);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!cancelled) {
                                        callback.onError(message);
                                    }
                                }
                            });
                        }
                    })
                    .build();
            transformer.start(composition, output.getAbsolutePath());
        } catch (Throwable t) {
            callback.onError(describe(t));
            return;
        }

        final ProgressHolder holder = new ProgressHolder();
        poller = new Runnable() {
            @Override
            public void run() {
                Transformer current = transformer;
                if (current == null || cancelled) {
                    return;
                }
                try {
                    current.getProgress(holder);
                } catch (Throwable ignored) {
                }
                int progress = Math.max(0, Math.min(99, holder.progress));
                callback.onProgress(progress);
                handler.postDelayed(this, 400);
            }
        };
        handler.postDelayed(poller, 400);
    }

    public void cancel() {
        cancelled = true;
        stopPolling();
        Transformer current = transformer;
        transformer = null;
        try {
            if (current != null) {
                current.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopPolling() {
        transformer = null;
        if (poller != null) {
            handler.removeCallbacks(poller);
            poller = null;
        }
    }

    private Composition buildComposition(Project project, int outWidth, int outHeight) {
        List<EditedMediaItem> items = new ArrayList<>();
        List<TextureOverlay> overlayList = new ArrayList<>();
        int clipCount = project.clips.size();

        EditedMediaItemSequence.Builder audioSequence = new EditedMediaItemSequence.Builder();
        int audioItems = 0;

        for (int i = 0; i < clipCount; i++) {
            Project.Clip clip = project.clips.get(i);
            if (clip.uri == null || clip.trimmedDurationMs() <= 0) {
                continue;
            }

            List<Effect> videoEffects = new ArrayList<>();
            videoEffects.add(Presentation.createForWidthAndHeight(
                    outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT));
            videoEffects.addAll(FilterPresets.effectsFor(clip));

            boolean muted = project.muteOriginal || clip.volume <= 0.01f;
            long clipOutputMs = clip.outputDurationMs();
            float videoSpeed = Math.max(0.1f, clip.speed);
            float audioSpeed = Math.max(0.1f, clip.audioSpeed);

            // Speed of the pictures only: sound is assembled in its own sequence below,
            // so «скорость видео» and «скорость звука» stay independent.
            if (!clip.image && Math.abs(videoSpeed - 1f) > 0.01f) {
                videoEffects.add(Effects.createExperimentalSpeedChangingEffect(
                        constantSpeed(videoSpeed)).second);
            }

            MediaItem item;
            EditedMediaItem.Builder editedBuilder;
            if (clip.image) {
                item = new MediaItem.Builder()
                        .setUri(Uri.parse(clip.uri))
                        .build();
                editedBuilder = new EditedMediaItem.Builder(item)
                        .setDurationUs(Math.max(100_000L, clipOutputMs * 1000L))
                        .setFrameRate(30)
                        .setRemoveAudio(true)
                        .setEffects(new Effects(new ArrayList<AudioProcessor>(), videoEffects));
            } else {
                item = new MediaItem.Builder()
                        .setUri(Uri.parse(clip.uri))
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.trimStartMs)
                                .setEndPositionMs(clip.trimEndMs)
                                .build())
                        .build();
                editedBuilder = new EditedMediaItem.Builder(item)
                        .setRemoveAudio(true)
                        .setEffects(new Effects(new ArrayList<AudioProcessor>(), videoEffects));
            }
            items.add(editedBuilder.build());

            // Sound for this clip: its own tempo, cut or padded to the video length.
            long slotUs = Math.max(100_000L, clipOutputMs * 1000L);
            long audioItemUs = 0;
            if (!clip.image && !muted) {
                long clippedAudioUs = Math.max(0L, clip.trimmedDurationMs() * 1000L);
                long usableUs = Math.min(clippedAudioUs, (long) (slotUs * audioSpeed));
                if (usableUs > 100_000L) {
                    long endMs = clip.trimStartMs + usableUs / 1000L;
                    MediaItem audioItem = new MediaItem.Builder()
                            .setUri(Uri.parse(clip.uri))
                            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(clip.trimStartMs)
                                    .setEndPositionMs(Math.max(clip.trimStartMs + 100, endMs))
                                    .build())
                            .build();
                    List<AudioProcessor> audioProcessors = new ArrayList<>();
                    if (Math.abs(clip.volume - 1f) > 0.01f) {
                        audioProcessors.add(new GainAudioProcessor(clip.volume));
                    }
                    if (Math.abs(audioSpeed - 1f) > 0.01f) {
                        SonicAudioProcessor sonic = new SonicAudioProcessor();
                        sonic.setSpeed(audioSpeed);
                        audioProcessors.add(sonic);
                    }
                    audioSequence.addItem(new EditedMediaItem.Builder(audioItem)
                            .setRemoveVideo(true)
                            .setEffects(new Effects(audioProcessors, new ArrayList<Effect>()))
                            .build());
                    audioItemUs = (long) (usableUs / audioSpeed);
                    audioItems++;
                }
            }
            long gapUs = slotUs - audioItemUs;
            if (gapUs > 100_000L) {
                audioSequence.addGap(gapUs);
            }

            long clipStartMs = project.startOfClip(i);
            float speed = videoSpeed;
            for (Project.TextItem text : clip.texts) {
                if (text.text == null || text.text.trim().isEmpty()) {
                    continue;
                }
                long startMs = clipStartMs + Math.min(clipOutputMs,
                        Math.round(Math.max(0, text.startMs) / speed));
                long endMs = clipStartMs + Math.min(clipOutputMs,
                        Math.round(Math.max(0, text.endMs) / speed));
                if (endMs <= startMs) {
                    continue;
                }
                PlacedText placed = placeText(text, startMs, endMs, outWidth, outHeight);
                if (placed != null) {
                    overlayList.add(timeLimitedOverlay(placed));
                }
            }
        }

        if (items.isEmpty()) {
            throw new IllegalStateException("В проекте нет готовых клипов");
        }

        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(new EditedMediaItemSequence(items));
        if (audioItems > 0) {
            sequences.add(audioSequence.build());
        }

        if (project.musicUri != null && !project.musicUri.isEmpty()) {
            List<AudioProcessor> musicAudio = new ArrayList<>();
            musicAudio.add(new GainAudioProcessor(Math.max(0f, Math.min(4f, project.musicVolume))));
            MediaItem music = new MediaItem.Builder()
                    .setUri(Uri.parse(project.musicUri))
                    .build();
            EditedMediaItem musicItem = new EditedMediaItem.Builder(music)
                    .setRemoveVideo(true)
                    .setEffects(new Effects(musicAudio, new ArrayList<Effect>()))
                    .build();
            sequences.add(new EditedMediaItemSequence.Builder(musicItem)
                    .setIsLooping(true)
                    .build());
        }

        // Guarantees one stable audio track even when some clips are silent.
        Composition.Builder builder = new Composition.Builder(sequences)
                .experimentalSetForceAudioTrack(true);
        if (!overlayList.isEmpty()) {
            List<Effect> compositionEffects = new ArrayList<>();
            compositionEffects.add(new OverlayEffect(overlayList));
            builder.setEffects(new Effects(new ArrayList<AudioProcessor>(), compositionEffects));
        }
        return builder.build();
    }

    private static TextureOverlay timeLimitedOverlay(final PlacedText placed) {
        return new BitmapOverlay() {
            @Override
            public Bitmap getBitmap(long presentationTimeUs) {
                return placed.bitmap;
            }

            @Override
            public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                boolean show = presentationTimeUs >= placed.startUs
                        && presentationTimeUs < placed.endUs;
                return show ? placed.visible : placed.hidden;
            }
        };
    }

    private static SpeedProvider constantSpeed(final float speed) {
        return new SpeedProvider() {
            @Override
            public float getSpeed(long timeUs) {
                return speed;
            }

            @Override
            public long getNextSpeedChangeTimeUs(long timeUs) {
                return C.TIME_UNSET;
            }
        };
    }

    private static PlacedText placeText(Project.TextItem text, long startMs, long endMs,
                                       int outWidth, int outHeight) {
        Bitmap bitmap = buildTextBitmap(text, outWidth, outHeight);
        if (bitmap == null) {
            return null;
        }
        float marginPx = outHeight * 0.05f;
        float offset = 2f * (marginPx + bitmap.getHeight() / 2f) / outHeight;
        float y;
        if (text.position == 0) {
            y = 1f - offset;
        } else if (text.position == 2) {
            y = -1f + offset;
        } else {
            y = 0f;
        }
        y = Math.max(-1f, Math.min(1f, y));
        OverlaySettings visible = new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, y)
                .setOverlayFrameAnchor(0f, 0f)
                .setScale(1f, 1f)
                .build();
        OverlaySettings hidden = new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, y)
                .setOverlayFrameAnchor(0f, 0f)
                .setScale(1f, 1f)
                .setAlphaScale(0f)
                .build();
        return new PlacedText(bitmap, visible, hidden, startMs * 1000L, endMs * 1000L);
    }

    private static Bitmap buildTextBitmap(Project.TextItem text, int outWidth, int outHeight) {
        float textSize = Math.max(16f, outHeight * text.size);
        int bitmapWidth = Math.max(2, Math.round(outWidth * 0.94f));
        int padding = Math.max(4, Math.round(textSize * 0.24f));
        int maxTextWidth = Math.max(1, bitmapWidth - padding * 2);

        TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(text.color | 0xFF000000);
        fill.setTextSize(textSize);
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        fill.setTextAlign(Paint.Align.CENTER);

        TextPaint stroke = new TextPaint(fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, textSize * 0.055f));
        stroke.setColor(0xCC000000);

        StaticLayout fillLayout = StaticLayout.Builder
                .obtain(text.text, 0, text.text.length(), fill, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(true)
                .build();
        StaticLayout strokeLayout = StaticLayout.Builder
                .obtain(text.text, 0, text.text.length(), stroke, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(true)
                .build();

        int bitmapHeight = Math.max(fillLayout.getHeight(), strokeLayout.getHeight())
                + padding * 2;
        if (bitmapHeight <= 0) {
            return null;
        }
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(padding, padding);
        strokeLayout.draw(canvas);
        fillLayout.draw(canvas);
        return bitmap;
    }

    private static int[] outputSize(float aspect, int shortSide) {
        int width;
        int height;
        if (aspect >= 1f) {
            height = shortSide;
            width = Math.round(shortSide * aspect);
        } else {
            width = shortSide;
            height = Math.round(shortSide / aspect);
        }
        width -= width % 2;
        height -= height % 2;
        return new int[]{Math.max(2, width), Math.max(2, height)};
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "Неизвестная ошибка";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        return message;
    }
}
