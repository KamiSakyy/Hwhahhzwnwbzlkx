package com.kamisakyy.webrecorder;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Foreground service that records the screen while the user browses inside the app.
 * Recording keeps running when the app goes to the background and can be stopped
 * from the notification or from the app itself.
 */
public class RecorderService extends Service {

    private static final String TAG = "RecorderService";

    public static final String ACTION_START = "com.kamisakyy.webrecorder.action.START";
    public static final String ACTION_STOP = "com.kamisakyy.webrecorder.action.STOP";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_MIC = "mic";

    public static final int NOTIFICATION_ID = 7331;

    public static final String PREFS = "webrecorder";
    public static final String PREF_LAST_FILE = "last_file";
    public static final String PREF_LAST_URI = "last_uri";
    public static final String PREF_QUALITY = "quality";

    public static final String[] QUALITY_NAMES = {"480p", "720p", "1080p", "Экран"};
    private static final int[] Q_SHORT_SIDE = {480, 720, 1080, 0};
    private static final int[] Q_BITRATE = {4_000_000, 8_000_000, 16_000_000, 20_000_000};
    private static final int FRAME_RATE = 30;
    private static final int SAMPLE_RATE = 44100;

    public interface Listener {
        void onRecorderState(boolean recording, int seconds, long bytes);

        void onRecorderSaved(Uri uri, String label);

        void onRecorderError(String message);
    }

    private static RecorderService instance;
    private static Listener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private File outputFile;

    private volatile boolean recording;
    private volatile boolean stopping;
    private long startedAt;

    private boolean withMic;
    private int qualityIndex;
    private int outWidth;
    private int outHeight;

    // ---- internal (app sound) audio path ----
    private AudioRecord internalAudioRecord;
    private MediaCodec audioCodec;
    private MediaMuxer audioMuxer;
    private File audioFile;
    private Thread audioReaderThread;
    private Thread audioDrainThread;
    private volatile boolean audioRunning;
    private volatile boolean audioEosSent;
    private int audioTrackIndex = -1;
    private boolean audioMuxerStarted;
    private final Object audioLock = new Object();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!recording) {
                return;
            }
            int seconds = getElapsedSeconds();
            long bytes = outputFile == null ? 0 : outputFile.length();
            updateNotification(seconds, bytes);
            dispatchState(seconds, bytes);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (recording) {
                finishRecording();
            } else if (!stopping) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (recording) {
                return START_NOT_STICKY;
            }
            startForeground(NOTIFICATION_ID, buildNotification("Подготовка к записи…", true));
            try {
                initRecording(intent);
            } catch (Throwable t) {
                Log.e(TAG, "init failed", t);
                failRecording("Не удалось начать запись: " + shortMessage(t));
            }
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------
    // Public helpers used by the UI
    // ------------------------------------------------------------------

    public static boolean isRecording() {
        RecorderService s = instance;
        return s != null && s.recording;
    }

    public static int getElapsedSeconds() {
        RecorderService s = instance;
        if (s == null || !s.recording) {
            return 0;
        }
        return (int) ((SystemClock.elapsedRealtime() - s.startedAt) / 1000L);
    }

    public static void setListener(Listener l) {
        listener = l;
        RecorderService s = instance;
        if (l != null && s != null && s.recording) {
            l.onRecorderState(true, getElapsedSeconds(),
                    s.outputFile == null ? 0 : s.outputFile.length());
        }
    }

    public static void requestStop(Context ctx) {
        Intent i = new Intent(ctx, RecorderService.class);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Recording start
    // ------------------------------------------------------------------

    private void initRecording(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            failRecording("Нет разрешения на запись экрана");
            return;
        }

        qualityIndex = Math.max(0, Math.min(QUALITY_NAMES.length - 1,
                intent.getIntExtra(EXTRA_QUALITY, 1)));
        withMic = intent.getBooleanExtra(EXTRA_MIC, true);

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            failRecording("MediaProjection недоступен на этом устройстве");
            return;
        }
        mediaProjection = mpm.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            failRecording("Не удалось получить доступ к экрану");
            return;
        }

        // Android 14+ requires a registered callback before creating the virtual display.
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (recording && !stopping) {
                            Log.w(TAG, "MediaProjection stopped by the system");
                            finishRecording();
                        }
                    }
                });
            }
        };
        mediaProjection.registerCallback(projectionCallback, handler);

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            failRecording("Нет доступа к параметрам экрана");
            return;
        }
        try {
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
        } catch (Throwable t) {
            dm.widthPixels = 1080;
            dm.heightPixels = 1920;
            dm.densityDpi = 420;
        }

        int[] size = resolveSize(dm);
        Log.i(TAG, "Requested recording size " + size[0] + "x" + size[1]
                + ", quality " + qualityIndex);

        File dir = getRecordingDir();
        String name = "WebRecorder_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        outputFile = new File(dir, name);

        // Try the requested size first, then fall back to lower resolutions so that
        // recording still starts on devices whose encoder has size limits.
        List<int[]> candidates = new ArrayList<>();
        candidates.add(size);
        int[] hd = scaleToShortSide(dm, 720);
        if (hd[0] != size[0] || hd[1] != size[1]) {
            candidates.add(hd);
        }
        int[] sd = scaleToShortSide(dm, 480);
        if (sd[0] != hd[0] || sd[1] != hd[1]) {
            candidates.add(sd);
        }

        MediaRecorder recorder = null;
        int[] chosen = null;
        boolean micUsed = withMic;
        for (int[] candidate : candidates) {
            if (withMic) {
                try {
                    recorder = createRecorder(outputFile, candidate[0], candidate[1],
                            Q_BITRATE[qualityIndex], FRAME_RATE, true);
                    chosen = candidate;
                    micUsed = true;
                    break;
                } catch (Throwable t) {
                    Log.w(TAG, "prepare " + candidate[0] + "x" + candidate[1]
                            + " with mic failed", t);
                }
            }
            try {
                recorder = createRecorder(outputFile, candidate[0], candidate[1],
                        Q_BITRATE[qualityIndex], FRAME_RATE, false);
                chosen = candidate;
                micUsed = false;
                break;
            } catch (Throwable t) {
                Log.w(TAG, "prepare " + candidate[0] + "x" + candidate[1] + " failed", t);
            }
        }

        if (recorder == null || chosen == null) {
            try {
                mediaProjection.stop();
            } catch (Throwable ignored) {
            }
            mediaProjection = null;
            failRecording("Кодек не поддерживает запись экрана. Выберите качество ниже.");
            return;
        }

        withMic = micUsed;
        outWidth = chosen[0];
        outHeight = chosen[1];
        Log.i(TAG, "Recording size " + outWidth + "x" + outHeight + ", mic=" + withMic);
        mediaRecorder = recorder;

        try {
            int density = dm.densityDpi > 0 ? dm.densityDpi : 320;
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "WebRecorder",
                    outWidth,
                    outHeight,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(),
                    null,
                    handler);
            if (virtualDisplay == null) {
                throw new IllegalStateException("virtual display is null");
            }
            mediaRecorder.start();
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            releaseRecorder();
            try {
                mediaProjection.stop();
            } catch (Throwable ignored) {
            }
            mediaProjection = null;
            failRecording("Не удалось запустить запись: " + shortMessage(t));
            return;
        }

        startedAt = SystemClock.elapsedRealtime();
        recording = true;
        stopping = false;

        if (!withMic) {
            // App sound capture (Android 10+). Skipped silently when unavailable.
            startInternalAudio();
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_LAST_FILE, outputFile.getAbsolutePath())
                .apply();

        lastNotification = null;
        updateNotification(0, 0);
        dispatchState(0, 0);
        handler.postDelayed(ticker, 1000);
    }

    private int[] resolveSize(DisplayMetrics dm) {
        int shortSide = Math.min(Math.max(1, dm.widthPixels), Math.max(1, dm.heightPixels));
        int targetShort;
        if (qualityIndex >= Q_SHORT_SIDE.length - 1 || Q_SHORT_SIDE[qualityIndex] == 0) {
            targetShort = shortSide;
        } else {
            targetShort = Math.min(Q_SHORT_SIDE[qualityIndex], shortSide);
        }
        return scaleToShortSide(dm, targetShort);
    }

    private int[] scaleToShortSide(DisplayMetrics dm, int targetShort) {
        int screenW = Math.max(1, dm.widthPixels);
        int screenH = Math.max(1, dm.heightPixels);
        int shortSide = Math.min(screenW, screenH);
        int target = Math.min(targetShort, shortSide);
        float scale = target / (float) shortSide;
        int w = Math.round(screenW * scale);
        int h = Math.round(screenH * scale);
        w = Math.max(2, w - (w % 2));
        h = Math.max(2, h - (h % 2));
        return new int[]{w, h};
    }

    private File getRecordingDir() {
        File dir = getExternalFilesDir("Recordings");
        if (dir == null) {
            dir = new File(getFilesDir(), "Recordings");
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private MediaRecorder createRecorder(File file, int w, int h, int bitrate, int fps,
                                         boolean mic) throws Exception {
        MediaRecorder r;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            r = new MediaRecorder(this);
        } else {
            //noinspection deprecation
            r = new MediaRecorder();
        }
        if (mic) {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        r.setOutputFile(file.getAbsolutePath());
        r.setVideoSize(w, h);
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        r.setVideoEncodingBitRate(bitrate);
        r.setVideoFrameRate(fps);
        if (mic) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(SAMPLE_RATE);
            r.setAudioEncodingBitRate(128_000);
            r.setAudioChannels(2);
        }
        try {
            r.prepare();
        } catch (Exception e) {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
            throw e;
        }
        return r;
    }

    // ------------------------------------------------------------------
    // Recording stop / save
    // ------------------------------------------------------------------

    private void finishRecording() {
        if (!recording || stopping) {
            return;
        }
        stopping = true;
        recording = false;
        handler.removeCallbacks(ticker);
        updateNotification("Сохранение видео…", true);
        dispatchState(0, outputFile == null ? 0 : outputFile.length());

        new Thread(new Runnable() {
            @Override
            public void run() {
                doFinishRecording();
            }
        }, "recorder-stop").start();
    }

    private void doFinishRecording() {
        Log.i(TAG, "Stopping recording");
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
            }
            virtualDisplay = null;
        }
        releaseRecorder();
        stopInternalAudio();

        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (Throwable ignored) {
            }
            try {
                mediaProjection.stop();
            } catch (Throwable ignored) {
            }
            mediaProjection = null;
        }

        if (audioFile != null) {
            File merged = mergeAudio(outputFile, audioFile);
            if (merged != null && outputFile != null) {
                File target = outputFile;
                boolean deleteOk = !target.exists() || target.delete();
                if (deleteOk && merged.renameTo(target)) {
                    //noinspection ResultOfMethodCallIgnored
                    audioFile.delete();
                } else {
                    outputFile = merged;
                }
            }
            if (audioFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                audioFile.delete();
            }
            audioFile = null;
        }

        final File result = outputFile;
        if (result == null || !result.exists() || result.length() == 0) {
            dispatchError("Запись пустая — не удалось сохранить видео");
            finishService();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_LAST_FILE, result.getAbsolutePath())
                .apply();

        dispatchState(0, result.length());
        updateNotification("Сохранение в Галерею…", true);

        Uri uri = RecorderStore.saveToGallery(getApplicationContext(), result, result.getName());
        if (uri != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_LAST_URI, uri.toString())
                    .apply();
            dispatchSaved(uri, "Галерея / Movies/" + RecorderStore.ALBUM);
            showSavedNotification(uri);
        } else {
            Log.w(TAG, "gallery auto-save failed");
        }
        finishService();
    }

    private void finishService() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 24) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        //noinspection deprecation
                        stopForeground(true);
                    }
                } catch (Throwable ignored) {
                }
                stopSelf();
            }
        });
    }

    private void releaseRecorder() {
        MediaRecorder r = mediaRecorder;
        mediaRecorder = null;
        if (r == null) {
            return;
        }
        try {
            r.stop();
        } catch (Throwable t) {
            Log.w(TAG, "MediaRecorder.stop failed", t);
        }
        try {
            r.reset();
        } catch (Throwable ignored) {
        }
        try {
            r.release();
        } catch (Throwable ignored) {
        }
    }

    private void failRecording(String message) {
        Log.e(TAG, "fail: " + message);
        recording = false;
        stopping = false;
        handler.removeCallbacks(ticker);
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Throwable ignored) {
        }
        virtualDisplay = null;
        releaseRecorder();
        stopInternalAudio();
        try {
            if (mediaProjection != null) {
                mediaProjection.stop();
            }
        } catch (Throwable ignored) {
        }
        mediaProjection = null;

        dispatchError(message);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID + 1, newBuilder(App.CHANNEL_EVENTS)
                        .setSmallIcon(R.drawable.ic_stat_rec)
                        .setContentTitle("Запись не удалась")
                        .setContentText(message)
                        .setAutoCancel(true)
                        .build());
            } catch (Throwable ignored) {
            }
        }
        finishService();
    }

    // ------------------------------------------------------------------
    // Internal audio capture (sound of the app, Android 10+)
    // ------------------------------------------------------------------

    private void startInternalAudio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                minBuffer = SAMPLE_RATE;
            }
            AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBuffer * 4, 16384))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                Log.w(TAG, "playback capture AudioRecord not initialized");
                return;
            }

            File file = new File(outputFile.getAbsolutePath() + ".audio.mp4");
            MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            internalAudioRecord = record;
            audioCodec = codec;
            audioMuxer = muxer;
            audioFile = file;
            audioRunning = true;
            audioEosSent = false;
            audioMuxerStarted = false;
            audioTrackIndex = -1;
            record.startRecording();

            audioReaderThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    audioReaderLoop();
                }
            }, "recorder-audio-reader");
            audioDrainThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    audioDrainLoop();
                }
            }, "recorder-audio-drain");
            audioReaderThread.start();
            audioDrainThread.start();
            Log.i(TAG, "internal audio capture started");
        } catch (Throwable t) {
            Log.w(TAG, "internal audio unavailable", t);
            stopInternalAudio();
            if (audioFile != null && audioFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                audioFile.delete();
            }
            audioFile = null;
        }
    }

    private void audioReaderLoop() {
        AudioRecord record = internalAudioRecord;
        MediaCodec codec = audioCodec;
        if (record == null || codec == null) {
            return;
        }
        byte[] pcm = new byte[8192];
        long frames = 0;
        while (audioRunning) {
            int n;
            try {
                n = record.read(pcm, 0, pcm.length);
            } catch (Throwable t) {
                break;
            }
            if (n < 0) {
                break;
            }
            if (n == 0) {
                continue;
            }
            try {
                int index = codec.dequeueInputBuffer(20_000);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    if (buffer != null) {
                        buffer.clear();
                        buffer.put(pcm, 0, n);
                        long pts = frames * 1_000_000L / SAMPLE_RATE;
                        codec.queueInputBuffer(index, 0, n, pts, 0);
                        frames += n / 4L;
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, 0);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "audio encode failed", t);
                break;
            }
        }
        try {
            int index = codec.dequeueInputBuffer(200_000);
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0,
                        frames * 1_000_000L / SAMPLE_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Throwable ignored) {
        }
        audioEosSent = true;
    }

    private void audioDrainLoop() {
        MediaCodec codec = audioCodec;
        if (codec == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long idleSince = 0;
        while (true) {
            if (!audioRunning && idleSince == 0) {
                idleSince = SystemClock.elapsedRealtime();
            }
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 10_000);
            } catch (Throwable t) {
                break;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (audioLock) {
                    if (audioMuxer != null && !audioMuxerStarted) {
                        try {
                            audioTrackIndex = audioMuxer.addTrack(codec.getOutputFormat());
                            audioMuxer.start();
                            audioMuxerStarted = true;
                        } catch (Throwable t) {
                            Log.w(TAG, "cannot start audio muxer", t);
                        }
                    }
                }
                continue;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!audioRunning && (audioEosSent
                        || SystemClock.elapsedRealtime() - idleSince > 700)) {
                    break;
                }
                continue;
            }
            if (index < 0) {
                continue;
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0 && audioMuxerStarted) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    synchronized (audioLock) {
                        if (audioMuxerStarted) {
                            audioMuxer.writeSampleData(audioTrackIndex, buffer, info);
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Throwable t) {
                Log.w(TAG, "audio mux failed", t);
                break;
            }
            if (eos) {
                break;
            }
        }
        audioEosSent = true;
    }

    private void stopInternalAudio() {
        audioRunning = false;
        AudioRecord record = internalAudioRecord;
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (Throwable ignored) {
            }
        }
        joinQuietly(audioReaderThread, 1200);
        joinQuietly(audioDrainThread, 1500);
        audioReaderThread = null;
        audioDrainThread = null;
        if (record != null) {
            try {
                record.release();
            } catch (Throwable ignored) {
            }
            internalAudioRecord = null;
        }
        MediaCodec codec = audioCodec;
        audioCodec = null;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
        }
        MediaMuxer muxer = audioMuxer;
        audioMuxer = null;
        if (muxer != null) {
            synchronized (audioLock) {
                if (audioMuxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
        }
        audioMuxerStarted = false;
        audioTrackIndex = -1;
    }

    private void joinQuietly(Thread thread, long millis) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(millis);
        } catch (Throwable ignored) {
        }
    }

    /** Merges the video-only MP4 with the separately encoded app sound. */
    private File mergeAudio(File video, File audio) {
        if (video == null || audio == null || !video.exists() || !audio.exists()
                || audio.length() == 0) {
            return null;
        }
        File merged = new File(video.getParentFile(), "merged_" + video.getName());
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(video.getAbsolutePath());
            int videoTrack = findTrack(videoExtractor, "video/");
            if (videoTrack < 0) {
                return null;
            }
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(audio.getAbsolutePath());
            int audioTrack = findTrack(audioExtractor, "audio/");
            if (audioTrack < 0) {
                return null;
            }

            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrack);

            muxer = new MediaMuxer(merged.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(videoFormat);
            int audioOut = muxer.addTrack(audioFormat);
            muxer.start();

            copyTrack(videoExtractor, videoTrack, muxer, videoOut);
            copyTrack(audioExtractor, audioTrack, muxer, audioOut);

            muxer.stop();
            muxer.release();
            muxer = null;
            Log.i(TAG, "audio merged into " + merged.getName());
            return merged;
        } catch (Throwable t) {
            Log.w(TAG, "mergeAudio failed", t);
            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            merged.delete();
            return null;
        } finally {
            try {
                if (videoExtractor != null) {
                    videoExtractor.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (audioExtractor != null) {
                    audioExtractor.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void copyTrack(MediaExtractor extractor, int srcTrack, MediaMuxer muxer,
                                  int dstTrack) {
        extractor.selectTrack(srcTrack);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(dstTrack, buffer, info);
            if (!extractor.advance()) {
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private String lastNotification;

    private Notification.Builder newBuilder(String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, channelId);
        }
        //noinspection deprecation
        return new Notification.Builder(this);
    }

    private Notification buildNotification(String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, open, flags);

        Notification.Builder builder = newBuilder(App.CHANNEL_RECORDING);
        builder.setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle("Web Recorder")
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (ongoing) {
            Intent stopIntent = new Intent(this, RecorderService.class);
            stopIntent.setAction(ACTION_STOP);
            PendingIntent stopPending = PendingIntent.getService(this, 2, stopIntent, flags);
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_rec), "Стоп", stopPending)
                    .build());
        }
        return builder.build();
    }

    private void updateNotification(int seconds, long bytes) {
        String text = "● " + formatTime(seconds) + " • " + QUALITY_NAMES[qualityIndex]
                + " • " + formatSize(bytes);
        updateNotification(text, true);
    }

    private void updateNotification(String text, boolean ongoing) {
        if (text.equals(lastNotification)) {
            return;
        }
        lastNotification = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(text, ongoing));
        } catch (Throwable ignored) {
        }
    }

    private void showSavedNotification(Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent open = PendingIntent.getActivity(this, 3, view, flags);
            Notification n = newBuilder(App.CHANNEL_EVENTS)
                    .setSmallIcon(R.drawable.ic_stat_rec)
                    .setContentTitle("Видео сохранено")
                    .setContentText("Галерея / Movies/" + RecorderStore.ALBUM)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID + 2, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "saved notification failed", t);
        }
    }

    private void dispatchState(int seconds, long bytes) {
        if (listener == null) {
            return;
        }
        final boolean rec = recording;
        final int sec = seconds;
        final long size = bytes;
        handler.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onRecorderState(rec, sec, size);
                }
            }
        });
    }

    private void dispatchSaved(final Uri uri, final String label) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onRecorderSaved(uri, label);
                }
            }
        });
    }

    private void dispatchError(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onRecorderError(message);
                }
            }
        });
    }

    private static String formatTime(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 МБ";
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1) {
            return String.format(Locale.US, "%.0f КБ", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f МБ", mb);
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return m;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        if (recording) {
            try {
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                }
            } catch (Throwable ignored) {
            }
            releaseRecorder();
            stopInternalAudio();
            try {
                if (mediaProjection != null) {
                    mediaProjection.stop();
                }
            } catch (Throwable ignored) {
            }
            recording = false;
        }
        instance = null;
        super.onDestroy();
    }
}
