package com.kamisakyy.cutpro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The editor: preview, timeline, tools and export. */
public class EditorActivity extends Activity {

    private static final int REQ_ADD_VIDEO = 2001;
    private static final int REQ_MUSIC = 2002;

    private Project project;
    private ExoPlayer player;
    private PlayerView playerView;
    private AspectFrameLayout previewBox;
    private FrameLayout textOverlay;
    private TextView bigPlay;
    private TextView btnPlay;
    private TextView timeText;
    private TextView emptyHint;
    private TextView projectTitle;
    private TextView timelineInfo;
    private TextView selectionInfo;
    private TextView btnExport;
    private TimelineView timeline;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<TextView> textViews = new ArrayList<>();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            ui.postDelayed(this, 100);
        }
    };

    private int selectedIndex = -1;
    private boolean exporting;
    private VideoExporter exporter;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private int pendingTextColor = 0xFFFFFFFF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String projectId = getIntent().getStringExtra("project_id");
        project = projectId == null ? null : ProjectStore.load(this, projectId);
        if (project == null) {
            Toast.makeText(this, "Проект не найден", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_editor);
        bindViews();
        setupPlayer();
        setupTimeline();
        setupActions();

        projectTitle.setText(project.name);
        previewBox.setAspectRatio(project.aspect);
        previewBox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft) {
                    rebuildTextOverlays();
                }
            }
        });

        if (project.clips.isEmpty()) {
            updateSelectionUi();
            emptyHint.setVisibility(View.VISIBLE);
        } else {
            selectedIndex = 0;
            rebuildPlaylist(0);
        }
        ui.postDelayed(ticker, 100);
    }

    private void bindViews() {
        playerView = findViewById(R.id.playerView);
        previewBox = findViewById(R.id.previewBox);
        textOverlay = findViewById(R.id.textOverlay);
        bigPlay = findViewById(R.id.bigPlay);
        btnPlay = findViewById(R.id.btnPlay);
        timeText = findViewById(R.id.timeText);
        emptyHint = findViewById(R.id.emptyHint);
        projectTitle = findViewById(R.id.projectTitle);
        timelineInfo = findViewById(R.id.timelineInfo);
        selectionInfo = findViewById(R.id.selectionInfo);
        btnExport = findViewById(R.id.btnExport);
        timeline = findViewById(R.id.timeline);
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);

        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                int index = player.getCurrentMediaItemIndex();
                if (index >= 0 && index < project.clips.size()) {
                    selectedIndex = index;
                    timeline.setSelectedIndex(index);
                    applyClipPlayback();
                    rebuildTextOverlays();
                    updateSelectionUi();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                updatePlayButton();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayButton();
            }
        });
    }

    private void setupTimeline() {
        timeline.setListener(new TimelineView.Listener() {
            @Override
            public void onSeekTo(long timeMs, int clipIndex) {
                seekToProjectTime(timeMs);
            }

            @Override
            public void onClipSelected(int clipIndex) {
                selectedIndex = clipIndex;
                timeline.setSelectedIndex(clipIndex);
                updateSelectionUi();
                rebuildTextOverlays();
            }
        });
    }

    private void setupActions() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddClip).setOnClickListener(v -> pickVideos());
        btnPlay.setOnClickListener(v -> togglePlay());
        bigPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.previewHost).setOnClickListener(v -> togglePlay());

        btnExport.setOnClickListener(v -> showExportDialog());

        projectTitle.setOnClickListener(v -> renameProject());

        findViewById(R.id.btnSplit).setOnClickListener(v -> splitClip());
        findViewById(R.id.btnTrim).setOnClickListener(v -> showTrimDialog());
        findViewById(R.id.btnDelete).setOnClickListener(v -> deleteClip());
        findViewById(R.id.btnMoveLeft).setOnClickListener(v -> moveClip(-1));
        findViewById(R.id.btnMoveRight).setOnClickListener(v -> moveClip(1));
        findViewById(R.id.btnDuplicate).setOnClickListener(v -> duplicateClip());
        findViewById(R.id.btnFilter).setOnClickListener(v -> showFilterDialog());
        findViewById(R.id.btnText).setOnClickListener(v -> showTextDialog());
        findViewById(R.id.btnSpeed).setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btnVolume).setOnClickListener(v -> showClipVolumeDialog());
        findViewById(R.id.btnAspect).setOnClickListener(v -> showAspectDialog());
        findViewById(R.id.btnMusic).setOnClickListener(v -> showMusicDialog());
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private Project.Clip selectedClip() {
        if (selectedIndex < 0 || selectedIndex >= project.clips.size()) {
            return null;
        }
        return project.clips.get(selectedIndex);
    }

    private void rebuildPlaylist(long seekToMs) {
        player.stop();
        player.clearMediaItems();
        for (Project.Clip clip : project.clips) {
            if (clip.uri == null) {
                continue;
            }
            MediaItem item = new MediaItem.Builder()
                    .setUri(Uri.parse(clip.uri))
                    .setMediaId(clip.id)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(Math.max(0, clip.trimStartMs))
                            .setEndPositionMs(Math.max(clip.trimStartMs + 100, clip.trimEndMs))
                            .build())
                    .build();
            player.addMediaItem(item);
        }
        if (project.clips.isEmpty()) {
            updateSelectionUi();
            updatePlayButton();
            return;
        }
        int first = Math.max(0, Math.min(selectedIndex, project.clips.size() - 1));
        player.setVideoEffects(FilterPresets.effectsFor(project.clips.get(first).filter));
        player.prepare();
        seekToProjectTime(seekToMs);
        updatePlayButton();
    }

    private void seekToProjectTime(long timeMs) {
        if (project.clips.isEmpty()) {
            return;
        }
        timeMs = Math.max(0, Math.min(timeMs, project.durationMs()));
        int index = project.clipIndexAt(timeMs);
        if (index < 0) {
            return;
        }
        long start = project.startOfClip(index);
        Project.Clip clip = project.clips.get(index);
        long position = Math.round((timeMs - start) * Math.max(0.1f, clip.speed));
        selectedIndex = index;
        player.seekTo(index, Math.max(0, position));
        timeline.setSelectedIndex(index);
        timeline.setPlayhead(timeMs);
        applyClipPlayback();
        rebuildTextOverlays();
        updateSelectionUi();
    }

    private void applyClipPlayback() {
        int index = player.getCurrentMediaItemIndex();
        if (index < 0 || index >= project.clips.size()) {
            index = selectedIndex;
        }
        if (index < 0 || index >= project.clips.size()) {
            return;
        }
        Project.Clip clip = project.clips.get(index);
        try {
            player.setVideoEffects(FilterPresets.effectsFor(clip.filter));
            player.setPlaybackParameters(new PlaybackParameters(Math.max(0.1f, clip.speed)));
        } catch (Throwable ignored) {
        }
    }

    private void togglePlay() {
        if (project.clips.isEmpty()) {
            pickVideos();
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            return;
        }
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            seekToProjectTime(0);
        }
        player.play();
    }

    private void updatePlayButton() {
        boolean playing = player != null && player.isPlaying();
        boolean empty = project == null || project.clips.isEmpty();
        btnPlay.setText(playing ? "❚❚" : "▶");
        bigPlay.setVisibility(playing || empty ? View.GONE : View.VISIBLE);
    }

    private void tick() {
        if (project == null || project.clips.isEmpty() || player == null) {
            return;
        }
        int index = player.getCurrentMediaItemIndex();
        if (index < 0 || index >= project.clips.size()) {
            return;
        }
        Project.Clip clip = project.clips.get(index);
        long position = Math.max(0, player.getCurrentPosition());
        long output = project.startOfClip(index)
                + (long) (position / Math.max(0.1f, clip.speed));
        output = Math.max(0, Math.min(output, project.durationMs()));
        timeline.setPlayhead(output);
        if (player.isPlaying() && !timeline.isDragging()) {
            timeline.ensureVisible(output);
        }
        timeText.setText(formatDuration(output) + " / " + formatDuration(project.durationMs()));
        updateTextVisibility(position);
    }

    private void updateTextVisibility(long positionInClip) {
        for (TextView view : textViews) {
            Project.TextItem item = (Project.TextItem) view.getTag();
            boolean visible = positionInClip >= item.startMs && positionInClip <= item.endMs;
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // Text overlays in the preview
    // ------------------------------------------------------------------

    private void rebuildTextOverlays() {
        textOverlay.removeAllViews();
        textViews.clear();
        int index = player == null ? -1 : player.getCurrentMediaItemIndex();
        if (index < 0) {
            index = selectedIndex;
        }
        if (index < 0 || index >= project.clips.size()) {
            return;
        }
        Project.Clip clip = project.clips.get(index);
        int height = previewBox.getHeight();
        if (height <= 0) {
            height = dp(240);
        }
        for (final Project.TextItem item : clip.texts) {
            if (item.text.trim().isEmpty()) {
                continue;
            }
            TextView view = new TextView(this);
            view.setText(item.text);
            view.setTextColor(item.color);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(10), height * item.size));
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setGravity(Gravity.CENTER);
            view.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
            view.setPadding(dp(8), dp(4), dp(8), dp(4));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            if (item.position == 0) {
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.topMargin = dp(24);
            } else if (item.position == 2) {
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(24);
            } else {
                lp.gravity = Gravity.CENTER;
            }
            lp.leftMargin = dp(12);
            lp.rightMargin = dp(12);
            view.setLayoutParams(lp);
            view.setTag(item);
            view.setVisibility(View.GONE);
            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDeleteText(item);
                    return true;
                }
            });
            textOverlay.addView(view);
            textViews.add(view);
        }
        updateTextVisibility(Math.max(0, player.getCurrentPosition()));
    }

    private void confirmDeleteText(final Project.TextItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить текст?")
                .setMessage(item.text)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    Project.Clip clip = selectedClip();
                    if (clip != null) {
                        clip.texts.remove(item);
                        saveProject();
                        rebuildTextOverlays();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    private void pickVideos() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_ADD_VIDEO);
        } catch (Throwable t) {
            toast("Не удалось открыть проводник");
        }
    }

    private void handlePickedVideos(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int firstIndex = project.clips.size();
        int added = 0;
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            long duration = readDuration(uri);
            if (duration < 400) {
                continue;
            }
            Project.Clip clip = new Project.Clip();
            clip.uri = uri.toString();
            clip.name = readName(uri);
            clip.sourceDurationMs = duration;
            clip.trimStartMs = 0;
            clip.trimEndMs = duration;
            project.clips.add(clip);
            added++;
        }
        if (added == 0) {
            toast("Не удалось прочитать видео");
            return;
        }
        saveProject();
        selectedIndex = firstIndex;
        rebuildPlaylist(project.startOfClip(firstIndex));
        toast(added + " видео добавлено");
    }

    private long readDuration(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private String readName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "Клип";
    }

    // ------------------------------------------------------------------
    // Editing operations
    // ------------------------------------------------------------------

    private void splitClip() {
        int index = player.getCurrentMediaItemIndex();
        if (index < 0) {
            index = selectedIndex;
        }
        if (index < 0 || index >= project.clips.size()) {
            toast("Выберите клип");
            return;
        }
        Project.Clip clip = project.clips.get(index);
        long position = Math.max(0, player.getCurrentPosition());
        long cut = clip.trimStartMs + position;
        if (cut <= clip.trimStartMs + 250 || cut >= clip.trimEndMs - 250) {
            toast("Поставьте курсор внутрь клипа");
            return;
        }
        long relative = cut - clip.trimStartMs;

        Project.Clip right = new Project.Clip();
        right.uri = clip.uri;
        right.name = clip.name;
        right.sourceDurationMs = clip.sourceDurationMs;
        right.trimStartMs = cut;
        right.trimEndMs = clip.trimEndMs;
        right.speed = clip.speed;
        right.volume = clip.volume;
        right.filter = clip.filter;

        List<Project.TextItem> moving = new ArrayList<>();
        for (Project.TextItem item : clip.texts) {
            long start = Math.max(0, item.startMs);
            long end = Math.min(clip.trimmedDurationMs(), item.endMs);
            if (end <= relative) {
                continue;
            }
            if (start >= relative) {
                Project.TextItem copy = copyText(item);
                copy.startMs = start - relative;
                copy.endMs = end - relative;
                moving.add(copy);
            } else {
                Project.TextItem copy = copyText(item);
                copy.startMs = 0;
                copy.endMs = end - relative;
                moving.add(copy);
                item.endMs = relative;
            }
        }
        right.texts.addAll(moving);

        clip.trimEndMs = cut;
        project.clips.add(index + 1, right);
        saveProject();
        selectedIndex = index + 1;
        rebuildPlaylist(project.startOfClip(index + 1));
        toast("Клип разрезан");
    }

    private Project.TextItem copyText(Project.TextItem source) {
        Project.TextItem item = new Project.TextItem();
        item.text = source.text;
        item.color = source.color;
        item.size = source.size;
        item.position = source.position;
        item.startMs = source.startMs;
        item.endMs = source.endMs;
        return item;
    }

    private void deleteClip() {
        int index = selectedIndex;
        if (index < 0 || index >= project.clips.size()) {
            toast("Выберите клип");
            return;
        }
        long time = project.startOfClip(index);
        project.clips.remove(index);
        saveProject();
        if (project.clips.isEmpty()) {
            player.stop();
            player.clearMediaItems();
            selectedIndex = -1;
            rebuildTextOverlays();
            updateSelectionUi();
            updatePlayButton();
            return;
        }
        selectedIndex = Math.min(index, project.clips.size() - 1);
        rebuildPlaylist(Math.min(time, project.durationMs() - 10));
        toast("Клип удалён");
    }

    private void moveClip(int direction) {
        int index = selectedIndex;
        int target = index + direction;
        if (index < 0 || index >= project.clips.size()
                || target < 0 || target >= project.clips.size()) {
            return;
        }
        Project.Clip clip = project.clips.remove(index);
        project.clips.add(target, clip);
        selectedIndex = target;
        saveProject();
        rebuildPlaylist(project.startOfClip(target));
    }

    private void duplicateClip() {
        int index = selectedIndex;
        if (index < 0 || index >= project.clips.size()) {
            toast("Выберите клип");
            return;
        }
        Project.Clip copy = Project.Clip.fromJson(project.clips.get(index).toJson());
        copy.id = java.util.UUID.randomUUID().toString();
        project.clips.add(index + 1, copy);
        saveProject();
        selectedIndex = index + 1;
        rebuildPlaylist(project.startOfClip(index + 1));
        toast("Клип дублирован");
    }

    private void showTrimDialog() {
        final Project.Clip clip = selectedClip();
        if (clip == null) {
            toast("Выберите клип");
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_trim, null);
        final SeekBar seekStart = view.findViewById(R.id.seekStart);
        final SeekBar seekEnd = view.findViewById(R.id.seekEnd);
        final TextView startLabel = view.findViewById(R.id.startLabel);
        final TextView endLabel = view.findViewById(R.id.endLabel);
        final TextView trimInfo = view.findViewById(R.id.trimInfo);
        final TextView trimDuration = view.findViewById(R.id.trimDuration);

        final int max = (int) Math.max(1, clip.sourceDurationMs);
        seekStart.setMax(max);
        seekEnd.setMax(max);
        seekStart.setProgress((int) Math.min(clip.trimStartMs, max));
        seekEnd.setProgress((int) Math.min(clip.trimEndMs, max));
        trimInfo.setText("Длительность исходника: " + formatSeconds(clip.sourceDurationMs));

        final Runnable update = new Runnable() {
            @Override
            public void run() {
                int start = Math.min(seekStart.getProgress(), seekEnd.getProgress() - 250);
                if (start < 0) {
                    start = 0;
                }
                int end = Math.max(seekEnd.getProgress(), start + 250);
                startLabel.setText("Начало: " + formatSeconds(start));
                endLabel.setText("Конец: " + formatSeconds(end));
                trimDuration.setText("В ролике: " + formatSeconds((long) (end - start)));
            }
        };
        update.run();

        seekStart.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress > seekEnd.getProgress() - 250) {
                    seekBar.setProgress(Math.max(0, seekEnd.getProgress() - 250));
                }
                update.run();
                player.seekTo(selectedIndex,
                        Math.max(0, seekBar.getProgress() - clip.trimStartMs));
            }
        });
        seekEnd.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < seekStart.getProgress() + 250) {
                    seekBar.setProgress(Math.min(max, seekStart.getProgress() + 250));
                }
                update.run();
                long preview = seekBar.getProgress() - clip.trimStartMs;
                preview = Math.max(0, Math.min(preview, clip.trimmedDurationMs() - 100));
                player.seekTo(selectedIndex, preview);
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();
        view.findViewById(R.id.btnTrimCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnTrimOk).setOnClickListener(v -> {
            long start = Math.max(0, Math.min(seekStart.getProgress(), max - 250));
            long end = Math.max(start + 250, Math.min(seekEnd.getProgress(), max));
            clip.trimStartMs = start;
            clip.trimEndMs = end;
            for (Project.TextItem item : clip.texts) {
                item.startMs = Math.min(item.startMs, clip.trimmedDurationMs());
                item.endMs = Math.min(item.endMs, clip.trimmedDurationMs());
                if (item.endMs < item.startMs) {
                    item.endMs = item.startMs;
                }
            }
            saveProject();
            dialog.dismiss();
            rebuildPlaylist(project.startOfClip(selectedIndex));
        });
        dialog.show();
    }

    private void showFilterDialog() {
        final Project.Clip clip = selectedClip();
        if (clip == null) {
            toast("Выберите клип");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Фильтр")
                .setSingleChoiceItems(FilterPresets.NAMES, clip.filter,
                        (dialog, which) -> {
                            clip.filter = which;
                            saveProject();
                            int index = player.getCurrentMediaItemIndex();
                            if (index == selectedIndex) {
                                player.setVideoEffects(FilterPresets.effectsFor(which));
                            }
                            updateSelectionUi();
                            dialog.dismiss();
                            toast("Фильтр: " + FilterPresets.NAMES[which]);
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showSpeedDialog() {
        final Project.Clip clip = selectedClip();
        if (clip == null) {
            toast("Выберите клип");
            return;
        }
        final String[] labels = {"0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "3x", "4x"};
        final float[] values = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f};
        int checked = 3;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - clip.speed) < 0.01f) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Скорость клипа")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    clip.speed = values[which];
                    saveProject();
                    dialog.dismiss();
                    rebuildPlaylist(project.startOfClip(selectedIndex));
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showClipVolumeDialog() {
        final Project.Clip clip = selectedClip();
        if (clip == null) {
            toast("Выберите клип");
            return;
        }
        showVolumeDialog("Громкость клипа", clip.volume, value -> {
            clip.volume = value;
            saveProject();
            toast("Громкость: " + Math.round(value * 100) + "%");
        });
    }

    private interface VolumeCallback {
        void onVolume(float value);
    }

    private void showVolumeDialog(String title, float initial, final VolumeCallback callback) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_volume, null);
        TextView titleView = view.findViewById(R.id.volumeTitle);
        final TextView valueView = view.findViewById(R.id.volumeValue);
        final SeekBar seekBar = view.findViewById(R.id.volumeSeek);
        titleView.setText(title);
        seekBar.setProgress(Math.round(initial * 100f));
        valueView.setText(Math.round(initial * 100f) + "%");

        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                valueView.setText(progress + "%");
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        view.findViewById(R.id.btnVolumeCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnVolumeOk).setOnClickListener(v -> {
            callback.onVolume(seekBar.getProgress() / 100f);
            dialog.dismiss();
        });
        dialog.show();
    }

    private void showAspectDialog() {
        final String[] labels = {"9:16 · Reels / TikTok", "16:9 · YouTube", "1:1 · Квадрат", "4:5 · Instagram"};
        final float[] values = {9f / 16f, 16f / 9f, 1f, 4f / 5f};
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - project.aspect) < 0.01f) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Формат кадра")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    project.aspect = values[which];
                    previewBox.setAspectRatio(project.aspect);
                    saveProject();
                    updateSelectionUi();
                    dialog.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showTextDialog() {
        final Project.Clip clip = selectedClip();
        if (clip == null) {
            toast("Выберите клип");
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_text, null);
        final EditText input = view.findViewById(R.id.textInput);
        final SeekBar sizeSeek = view.findViewById(R.id.sizeSeek);
        final SeekBar startSeek = view.findViewById(R.id.textStartSeek);
        final SeekBar endSeek = view.findViewById(R.id.textEndSeek);
        final TextView timeLabel = view.findViewById(R.id.textTimeLabel);
        final RadioGroup positions = view.findViewById(R.id.positionGroup);
        final LinearLayout colorRow = view.findViewById(R.id.colorRow);

        final int duration = (int) Math.max(300, clip.trimmedDurationMs());
        startSeek.setMax(duration);
        endSeek.setMax(duration);
        endSeek.setProgress(duration);
        sizeSeek.setProgress(30);
        pendingTextColor = 0xFFFFFFFF;

        final int[] colors = {
                0xFFFFFFFF, 0xFF000000, 0xFF00E5A0, 0xFFFFD400, 0xFFFF4D5E, 0xFF7C6CFF
        };
        buildColorChips(colorRow, colors);

        startSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress > endSeek.getProgress() - 250) {
                    seekBar.setProgress(Math.max(0, endSeek.getProgress() - 250));
                }
                updateTextTimeLabel(timeLabel, startSeek.getProgress(), endSeek.getProgress());
            }
        });
        endSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < startSeek.getProgress() + 250) {
                    seekBar.setProgress(Math.min(duration, startSeek.getProgress() + 250));
                }
                updateTextTimeLabel(timeLabel, startSeek.getProgress(), endSeek.getProgress());
            }
        });
        updateTextTimeLabel(timeLabel, 0, duration);

        final AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        view.findViewById(R.id.btnTextCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnTextOk).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                toast("Введите текст");
                return;
            }
            Project.TextItem item = new Project.TextItem();
            item.text = text;
            item.color = pendingTextColor;
            item.size = 0.025f + (sizeSeek.getProgress() / 100f) * 0.10f;
            int checked = positions.getCheckedRadioButtonId();
            item.position = checked == R.id.posTop ? 0 : checked == R.id.posBottom ? 2 : 1;
            item.startMs = startSeek.getProgress();
            item.endMs = Math.max(item.startMs + 250, endSeek.getProgress());
            clip.texts.add(item);
            saveProject();
            dialog.dismiss();
            rebuildTextOverlays();
            toast("Текст добавлен");
        });
        dialog.show();
    }

    private void updateTextTimeLabel(TextView label, int start, int end) {
        label.setText("Показ: " + formatSeconds(start) + " – " + formatSeconds(end));
    }

    private void buildColorChips(final LinearLayout row, final int[] colors) {
        row.removeAllViews();
        for (final int color : colors) {
            View chip = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.rightMargin = dp(10);
            chip.setLayoutParams(lp);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(color);
            background.setStroke(dp(pendingTextColor == color ? 3 : 1),
                    pendingTextColor == color ? 0xFF00E5A0 : 0xFF3A3A44);
            chip.setBackground(background);
            chip.setOnClickListener(v -> {
                pendingTextColor = color;
                buildColorChips(row, colors);
            });
            row.addView(chip);
        }
    }

    private void showMusicDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        box.setPadding(padding, padding, padding, dp(10));

        TextView title = new TextView(this);
        title.setText("Музыка");
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView name = new TextView(this);
        name.setText(project.musicUri == null ? "Трек не выбран" : project.musicName);
        name.setTextColor(getColor(R.color.dim));
        name.setTextSize(12);
        name.setPadding(0, dp(6), 0, dp(14));
        box.addView(name);

        final TextView volumeValue = new TextView(this);
        volumeValue.setTextColor(getColor(R.color.accent));
        volumeValue.setTextSize(13);
        volumeValue.setText("Громкость музыки: " + Math.round(project.musicVolume * 100) + "%");
        box.addView(volumeValue);

        final SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Math.round(project.musicVolume * 100));
        volume.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText("Громкость музыки: " + progress + "%");
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                project.musicVolume = seekBar.getProgress() / 100f;
                saveProject();
            }
        });
        box.addView(volume);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.RIGHT);
        buttons.setPadding(0, dp(12), 0, 0);

        Button pick = new Button(this);
        pick.setText("Выбрать трек");
        pick.setAllCaps(false);
        pick.setTextSize(13);
        pick.setBackgroundResource(R.drawable.bg_accent);
        pick.setTextColor(0xFF07110D);
        pick.setMinHeight(0);
        pick.setMinWidth(0);
        pick.setHeight(dp(44));
        pick.setPadding(dp(18), 0, dp(18), 0);
        pick.setOnClickListener(v -> {
            pickMusic();
        });
        buttons.addView(pick);

        if (project.musicUri != null) {
            Button remove = new Button(this);
            remove.setText("Убрать");
            remove.setAllCaps(false);
            remove.setTextSize(13);
            remove.setBackgroundResource(R.drawable.bg_pill);
            remove.setTextColor(getColor(R.color.text));
            remove.setMinHeight(0);
            remove.setMinWidth(0);
            remove.setHeight(dp(44));
            remove.setPadding(dp(18), 0, dp(18), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            remove.setLayoutParams(lp);
            remove.setOnClickListener(v -> {
                project.musicUri = null;
                project.musicName = null;
                saveProject();
                toast("Музыка убрана");
                dialogRef.dismiss();
                showMusicDialog();
            });
            buttons.addView(remove);
        }
        box.addView(buttons);

        dialogRef = new AlertDialog.Builder(this).setView(box).create();
        dialogRef.show();
    }

    private AlertDialog dialogRef;

    private void pickMusic() {
        if (dialogRef != null) {
            dialogRef.dismiss();
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        try {
            startActivityForResult(intent, REQ_MUSIC);
        } catch (Throwable t) {
            toast("Не удалось выбрать музыку");
        }
    }

    private void handlePickedMusic(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        project.musicUri = uri.toString();
        project.musicName = readName(uri);
        saveProject();
        toast("Музыка: " + project.musicName);
        showMusicDialog();
    }

    // ------------------------------------------------------------------
    // Project
    // ------------------------------------------------------------------

    private void renameProject() {
        final EditText input = new EditText(this);
        input.setText(project.name);
        input.setSingleLine(true);
        input.setSelection(project.name.length());
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("Название проекта")
                .setView(input)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().trim();
                        if (!value.isEmpty()) {
                            project.name = value;
                            projectTitle.setText(value);
                            saveProject();
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void saveProject() {
        if (project != null) {
            ProjectStore.save(this, project);
        }
    }

    private void updateSelectionUi() {
        boolean hasClips = !project.clips.isEmpty();
        boolean hasSelection = selectedIndex >= 0 && selectedIndex < project.clips.size();
        timeline.setProject(project);
        timeline.setSelectedIndex(selectedIndex);

        int[] tools = {
                R.id.btnSplit, R.id.btnTrim, R.id.btnDelete, R.id.btnMoveLeft,
                R.id.btnMoveRight, R.id.btnDuplicate, R.id.btnFilter, R.id.btnText,
                R.id.btnSpeed, R.id.btnVolume
        };
        for (int id : tools) {
            View v = findViewById(id);
            v.setEnabled(hasSelection);
            v.setAlpha(hasSelection ? 1f : 0.35f);
        }
        btnExport.setEnabled(hasClips);
        btnExport.setAlpha(hasClips ? 1f : 0.4f);
        emptyHint.setVisibility(hasClips ? View.GONE : View.VISIBLE);
        bigPlay.setVisibility(hasClips ? bigPlay.getVisibility() : View.GONE);

        if (hasSelection) {
            Project.Clip clip = project.clips.get(selectedIndex);
            selectionInfo.setText("Клип " + (selectedIndex + 1) + "/" + project.clips.size()
                    + " · " + formatDuration(clip.outputDurationMs())
                    + " · " + FilterPresets.name(clip.filter)
                    + " · " + String.format(Locale.US, "%.2fx", clip.speed)
                    + (clip.texts.isEmpty() ? "" : " · текстов: " + clip.texts.size()));
        } else {
            selectionInfo.setText(hasClips ? "Выберите клип на таймлайне" : "Добавьте видео, чтобы начать");
        }
        timelineInfo.setText(project.aspectLabel() + " · "
                + formatDuration(project.durationMs()) + " · "
                + project.clips.size() + " клип.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_ADD_VIDEO) {
            handlePickedVideos(data);
        } else if (requestCode == REQ_MUSIC) {
            handlePickedMusic(data);
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private void showExportDialog() {
        if (project.clips.isEmpty()) {
            toast("Добавьте хотя бы один клип");
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_export, null);
        TextView summary = view.findViewById(R.id.exportSummary);
        summary.setText(project.clips.size() + " клип. · "
                + formatDuration(project.durationMs()) + " · " + project.aspectLabel()
                + (project.musicUri == null ? "" : " · с музыкой"));
        final AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        view.findViewById(R.id.btnExportCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnExportStart).setOnClickListener(v -> {
            RadioGroup group = view.findViewById(R.id.resolutionGroup);
            int height = group.getCheckedRadioButtonId() == R.id.res720 ? 720 : 1080;
            dialog.dismiss();
            startExport(height);
        });
        dialog.show();
    }

    private void startExport(int height) {
        if (exporting) {
            toast("Экспорт уже идёт");
            return;
        }
        File base = getExternalFilesDir("exports");
        if (base == null) {
            base = getCacheDir();
        }
        File dir = new File(base, "");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String name = "CutPro_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".mp4";
        final File output = new File(dir, name);

        exporting = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showProgressDialog();

        exporter = new VideoExporter(this);
        exporter.export(project, height, output, new VideoExporter.Callback() {
            @Override
            public void onProgress(int percent) {
                if (progressBar != null) {
                    progressBar.setProgress(Math.max(0, Math.min(100, percent)));
                }
                if (progressText != null) {
                    progressText.setText(Math.max(0, Math.min(100, percent)) + "%");
                }
            }

            @Override
            public void onDone(File file, int width, int outHeight, long durationMs) {
                exporting = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                saveResultToGallery(file);
            }

            @Override
            public void onError(String message) {
                exporting = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                dismissProgress();
                new AlertDialog.Builder(EditorActivity.this)
                        .setTitle("Ошибка экспорта")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    private void saveResultToGallery(final File file) {
        if (progressText != null) {
            progressText.setText("Сохранение в Галерею…");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri uri = MediaSaver.saveVideo(
                        EditorActivity.this, file, file.getName());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress();
                        if (uri != null) {
                            showDoneDialog(uri);
                        } else {
                            new AlertDialog.Builder(EditorActivity.this)
                                    .setTitle("Видео готово")
                                    .setMessage("Файл: " + file.getAbsolutePath()
                                            + "\nНе удалось добавить в Галерею.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                });
            }
        }, "gallery-save").start();
    }

    private void showDoneDialog(final Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("Готово!")
                .setMessage("Видео сохранено в Галерею: Movies/" + MediaSaver.ALBUM)
                .setPositiveButton("Открыть", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(intent);
                        } catch (Throwable t) {
                            toast("Не удалось открыть видео");
                        }
                    }
                })
                .setNegativeButton("Готово", null)
                .show();
    }

    private void showProgressDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        progressBar = view.findViewById(R.id.progressBar);
        progressText = view.findViewById(R.id.progressText);
        progressBar.setProgress(0);
        progressText.setText("0%");
        progressDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create();
        view.findViewById(R.id.btnCancelExport).setOnClickListener(v -> {
            if (exporter != null) {
                exporter.cancel();
            }
            exporting = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            dismissProgress();
            toast("Экспорт отменён");
        });
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignored) {
            }
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onPause() {
        super.onPause();
        saveProject();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && !exporting) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        saveProject();
        if (project != null && project.clips.isEmpty()) {
            ProjectStore.delete(this, project);
        }
        if (exporter != null) {
            exporter.cancel();
            exporter = null;
        }
        dismissProgress();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (exporting) {
            toast("Дождитесь окончания экспорта");
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.1f с", ms / 1000f);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
