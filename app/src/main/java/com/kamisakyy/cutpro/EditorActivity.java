package com.kamisakyy.cutpro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compact CapCut-style editor: preview, timeline, contextual tool panel and export. */
public class EditorActivity extends Activity {

    private static final int REQ_ADD_VIDEO = 2001;
    private static final int REQ_MUSIC = 2002;
    private static final int REQ_AUDIO_VIDEO = 2003;
    private static final int REQ_PICK_MEDIA = 2004;
    private static final int REQ_PICK_FILES = 2005;

    private static final String P_TRIM = "trim";
    private static final String P_SPEED = "speed";
    private static final String P_VOLUME = "volume";
    private static final String P_FILTER = "filter";
    private static final String P_TEXT = "text";
    private static final String P_MUSIC = "music";
    private static final String P_ASPECT = "aspect";
    private static final String P_MORE = "more";
    private static final String P_EXPORT = "export";

    private Project project;
    private ExoPlayer player;
    private ExoPlayer musicPlayer;
    private PlayerView playerView;
    private AspectFrameLayout previewBox;
    private FrameLayout textOverlay;
    private ImageView imagePreview;
    private FrameLayout panelHost;
    private ImageView centerPlay;
    private ImageView btnUndo;
    private ImageView btnRedo;
    private TextView projectTitle;
    private TextView timeText;
    private TextView metaText;
    private View btnExport;
    private View emptyHint;
    private TimelineView timeline;
    private LinearLayout toolRail;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<TextView> textViews = new ArrayList<>();
    private final Map<String, View> railItems = new HashMap<>();
    private final List<Integer> playerClipIndexes = new ArrayList<>();
    private final Map<String, Bitmap> photoCache = new HashMap<>();
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    private boolean photoMode;
    private int photoClipIndex = -1;
    private long photoPositionMs;
    private boolean photoPlaying;
    private long photoLastTickMs;

    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            ui.postDelayed(this, 120);
        }
    };

    private int selectedIndex = -1;
    private String activePanel;
    private boolean exporting;
    private boolean trimGesture;
    private boolean updatingSlider;
    private int exportShortSide = 1080;
    private int pendingTextColor = 0xFFFFFFFF;
    private int pendingTextPosition = 1;
    private boolean skipPanelAnimation;
    private long[] loadedTrimStarts = new long[0];

    private VideoExporter exporter;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

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
        setupMusicPlayer();
        setupTimeline();
        buildRail();
        wireTopBar();

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

        if (!project.clips.isEmpty()) {
            selectedIndex = 0;
            rebuildPlaylist(0);
        } else {
            updateChrome();
            rebuildTextOverlays();
        }
        ui.postDelayed(ticker, 120);
    }

    private void bindViews() {
        playerView = findViewById(R.id.playerView);
        previewBox = findViewById(R.id.previewBox);
        textOverlay = findViewById(R.id.textOverlay);
        imagePreview = findViewById(R.id.imagePreview);
        panelHost = findViewById(R.id.panelHost);
        centerPlay = findViewById(R.id.centerPlay);
        projectTitle = findViewById(R.id.projectTitle);
        timeText = findViewById(R.id.timeText);
        metaText = findViewById(R.id.metaText);
        btnExport = findViewById(R.id.btnExport);
        emptyHint = findViewById(R.id.emptyHint);
        timeline = findViewById(R.id.timeline);
        toolRail = findViewById(R.id.toolRail);
        btnUndo = findViewById(R.id.btnUndo);
        btnRedo = findViewById(R.id.btnRedo);
    }

    private void wireTopBar() {
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (activePanel != null) {
                closePanel();
            } else {
                finish();
            }
        });
        btnExport.setOnClickListener(v -> togglePanel(P_EXPORT));
        btnUndo.setOnClickListener(v -> undo());
        btnRedo.setOnClickListener(v -> redo());
        projectTitle.setOnClickListener(v -> renameProject());
        findViewById(R.id.previewHost).setOnClickListener(v -> togglePlay());
        previewBox.setOnClickListener(v -> togglePlay());
        emptyHint.setOnClickListener(v -> pickMedia());
        centerPlay.setOnClickListener(v -> togglePlay());
    }

    // ------------------------------------------------------------------
    // Tool rail
    // ------------------------------------------------------------------

    private void buildRail() {
        String[] keys = {"add", "files", P_TRIM, "split", P_SPEED, P_VOLUME, P_FILTER, P_TEXT,
                P_MUSIC, P_ASPECT, P_MORE};
        int[] icons = {R.drawable.ic_add, R.drawable.ic_folder, R.drawable.ic_trim,
                R.drawable.ic_split, R.drawable.ic_speed, R.drawable.ic_volume,
                R.drawable.ic_filter, R.drawable.ic_text, R.drawable.ic_music,
                R.drawable.ic_aspect, R.drawable.ic_more};
        String[] labels = {"Галерея", "Файлы", "Обрезка", "Разрез", "Скорость", "Звук",
                "Фильтр", "Текст", "Музыка", "Формат", "Ещё"};

        toolRail.removeAllViews();
        railItems.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            View item = inflater.inflate(R.layout.item_tool, toolRail, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(58));
            lp.rightMargin = dp(3);
            item.setLayoutParams(lp);

            ImageView icon = item.findViewById(R.id.toolIcon);
            TextView label = item.findViewById(R.id.toolLabel);
            icon.setImageResource(icons[i]);
            icon.setContentDescription(labels[i]);
            label.setText(labels[i]);
            label.setSelected(false);

            item.setOnClickListener(v -> onToolClick(key));
            toolRail.addView(item);
            railItems.put(key, item);
        }
        refreshRail();
    }

    private void onToolClick(String key) {
        if ("add".equals(key)) {
            pickMedia();
            return;
        }
        if ("files".equals(key)) {
            pickFiles();
            return;
        }
        if ("split".equals(key)) {
            splitClip();
            return;
        }
        boolean needsClip = !P_ASPECT.equals(key) && !P_EXPORT.equals(key) && !P_MUSIC.equals(key);
        if (needsClip && selectedClip() == null) {
            toast("Выберите клип на таймлайне");
            return;
        }
        togglePanel(key);
    }

    private void refreshRail() {
        for (Map.Entry<String, View> entry : railItems.entrySet()) {
            View item = entry.getValue();
            boolean active = entry.getKey().equals(activePanel);
            item.setBackground(ripple(active
                    ? R.drawable.bg_tool_selected : R.drawable.bg_rail, 0x33FFFFFF));
            ImageView icon = item.findViewById(R.id.toolIcon);
            TextView label = item.findViewById(R.id.toolLabel);
            if (icon != null) {
                icon.setImageTintList(ColorStateList.valueOf(
                        getColor(active ? R.color.accent : R.color.text)));
            }
            if (label != null) {
                label.setTextColor(getColor(active ? R.color.accent : R.color.dim));
            }
            item.animate().scaleX(active ? 1.04f : 1f)
                    .scaleY(active ? 1.04f : 1f).setDuration(120).start();
        }
    }

    private void togglePanel(String key) {
        if (key.equals(activePanel)) {
            closePanel();
            return;
        }
        activePanel = key;
        timeline.setTrimMode(P_TRIM.equals(key));
        refreshRail();
        showPanel(key);
    }

    private void closePanel() {
        activePanel = null;
        panelHost.removeAllViews();
        panelHost.setVisibility(View.GONE);
        timeline.setTrimMode(false);
        refreshRail();
    }

    private void refreshPanel() {
        if (activePanel == null) {
            panelHost.removeAllViews();
        } else {
            skipPanelAnimation = true;
            showPanel(activePanel);
            skipPanelAnimation = false;
        }
    }

    private void showPanel(String key) {
        panelHost.removeAllViews();
        panelHost.setVisibility(View.VISIBLE);
        boolean needsClip = P_TRIM.equals(key) || P_SPEED.equals(key) || P_VOLUME.equals(key)
                || P_FILTER.equals(key) || P_TEXT.equals(key) || P_MORE.equals(key);
        if (needsClip && selectedClip() == null) {
            closePanel();
            return;
        }
        View panel;
        if (P_TRIM.equals(key)) {
            panel = trimPanel();
        } else if (P_SPEED.equals(key)) {
            panel = speedPanel();
        } else if (P_VOLUME.equals(key)) {
            panel = volumePanel();
        } else if (P_FILTER.equals(key)) {
            panel = filterPanel();
        } else if (P_TEXT.equals(key)) {
            panel = textPanel();
        } else if (P_MUSIC.equals(key)) {
            panel = musicPanel();
        } else if (P_ASPECT.equals(key)) {
            panel = aspectPanel();
        } else if (P_MORE.equals(key)) {
            panel = morePanel();
        } else if (P_EXPORT.equals(key)) {
            panel = exportPanel();
        } else {
            panel = new LinearLayout(this);
        }
        panelHost.addView(panel);
        if (!skipPanelAnimation) {
            panel.setAlpha(0f);
            panel.setTranslationY(dp(8));
            panel.animate().alpha(1f).translationY(0f).setDuration(150).start();
        }
    }

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------

    private LinearLayout newPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(8));
        box.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private Drawable ripple(int resId, int rippleColor) {
        Drawable content = getDrawable(resId);
        if (content == null) {
            return null;
        }
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private TextView chip(String text, boolean selected, Runnable action) {
        AutoFitTextView view = new AutoFitTextView(this);
        view.setText(text);
        view.setTextSize(10.5f);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(1);
        view.setPadding(dp(12), dp(7), dp(12), dp(7));
        view.setMinHeight(dp(30));
        view.setBackground(ripple(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip,
                selected ? 0x3306231B : 0x33FFFFFF));
        view.setTextColor(getColor(selected ? R.color.on_accent : R.color.text));
        if (selected) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        view.setLayoutParams(lp);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView dangerChip(String text, Runnable action) {
        TextView view = chip(text, false, action);
        view.setBackgroundResource(R.drawable.bg_chip_danger);
        view.setTextColor(getColor(R.color.danger));
        return view;
    }

    private TextView primaryChip(String text, Runnable action) {
        TextView view = chip(text, true, action);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(16), 0, dp(16), 0);
        return view;
    }

    /** Multi-line secondary text that may wrap instead of shrinking to unreadable size. */
    private TextView note(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.dim));
        view.setTextSize(10f);
        view.setLineSpacing(dp(1), 1f);
        view.setMaxLines(2);
        return view;
    }

    private TextView caption(String text) {
        AutoFitTextView view = new AutoFitTextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.dim));
        view.setTextSize(10f);
        return view;
    }

    /** Small title row at the top of every tool panel. */
    private View panelHeader(String title, String value) {
        LinearLayout header = row();
        header.setPadding(0, dp(2), 0, dp(6));

        AutoFitTextView name = new AutoFitTextView(this);
        name.setText(title);
        name.setTextSize(11f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.text));
        header.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) {
            AutoFitTextView extra = new AutoFitTextView(this);
            extra.setText(value);
            extra.setTextSize(10f);
            extra.setGravity(Gravity.RIGHT);
            extra.setTextColor(getColor(R.color.accent));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            header.addView(extra, lp);
        }
        return header;
    }

    private void addHeader(LinearLayout box, String title, String value) {
        box.addView(panelHeader(title, value), 0);
    }

    private void addHeader(View panel, String title, String value) {
        if (panel instanceof LinearLayout) {
            addHeader((LinearLayout) panel, title, value);
        }
    }

    private SeekBar panelSlider(int max, int progress, SeekBar.OnSeekBarChangeListener listener) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setThumbTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface3)));
        bar.setOnSeekBarChangeListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(26), 1f);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        bar.setLayoutParams(lp);
        return bar;
    }

    private interface SliderCallback {
        void onProgress(int progress, TextView value);

        void onDone();
    }

    private void addSliderRow(LinearLayout parent, String label, int max, int progress,
                              String initial, final SliderCallback callback) {
        LinearLayout line = row();
        line.setPadding(0, dp(2), 0, 0);

        TextView name = caption(label);
        name.setTextSize(9.5f);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                dp(54), ViewGroup.LayoutParams.WRAP_CONTENT);
        line.addView(name, nameLp);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setThumbTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface3)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(24), 1f);
        barLp.leftMargin = dp(6);
        barLp.rightMargin = dp(6);
        line.addView(bar, barLp);

        final TextView value = new AutoFitTextView(this);
        value.setText(initial);
        value.setTextColor(getColor(R.color.text));
        value.setTextSize(9.5f);
        value.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                dp(50), ViewGroup.LayoutParams.WRAP_CONTENT);
        line.addView(value, valueLp);

        bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pushUndo();
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                if (fromUser) {
                    callback.onProgress(p, value);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                callback.onDone();
                saveProject();
                rebuildPlaylist(currentProjectTime());
                refreshPanel();
            }
        });

        parent.addView(line);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private View filterPanel() {
        final Project.Clip clip = selectedClip();
        LinearLayout box = newPanel();
        addHeader(box, "Фильтр и цвет", null);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        for (int i = 0; i < FilterPresets.NAMES.length; i++) {
            final int index = i;
            chips.addView(chip(FilterPresets.NAMES[i], clip.filter == i, () -> {
                pushUndo();
                clip.filter = index;
                saveProject();
                rebuildPlaylist(currentProjectTime());
                refreshPanel();
            }));
        }
        scroll.addView(chips);
        box.addView(scroll);

        addSliderRow(box, "ЯРК", 200, Math.round(clip.brightness * 100) + 100,
                signed(Math.round(clip.brightness * 100)), new SliderCallback() {
                    @Override
                    public void onProgress(int progress, TextView value) {
                        clip.brightness = (progress - 100) / 100f;
                        value.setText(signed(progress - 100));
                    }

                    @Override
                    public void onDone() {
                    }
                });

        addSliderRow(box, "КОНТР", 100, Math.round(clip.contrast * 100) - 50,
                Math.round(clip.contrast * 100) + "%", new SliderCallback() {
                    @Override
                    public void onProgress(int progress, TextView value) {
                        clip.contrast = (progress + 50) / 100f;
                        value.setText((progress + 50) + "%");
                    }

                    @Override
                    public void onDone() {
                    }
                });

        addSliderRow(box, "НАСЫЩ", 200, Math.round(clip.saturation * 100) + 100,
                signed(Math.round(clip.saturation * 100)), new SliderCallback() {
                    @Override
                    public void onProgress(int progress, TextView value) {
                        clip.saturation = (progress - 100) / 100f;
                        value.setText(signed(progress - 100));
                    }

                    @Override
                    public void onDone() {
                    }
                });

        addSliderRow(box, "ТЕПЛО", 200, Math.round(clip.warmth * 100) + 100,
                signed(Math.round(clip.warmth * 100)), new SliderCallback() {
                    @Override
                    public void onProgress(int progress, TextView value) {
                        clip.warmth = (progress - 100) / 100f;
                        value.setText(signed(progress - 100));
                    }

                    @Override
                    public void onDone() {
                    }
                });

        LinearLayout actions = row();
        actions.setPadding(0, dp(2), 0, 0);
        actions.addView(chip("Ко всем клипам", false, () -> {
            pushUndo();
            for (Project.Clip other : project.clips) {
                if (other == clip) {
                    continue;
                }
                other.filter = clip.filter;
                other.brightness = clip.brightness;
                other.contrast = clip.contrast;
                other.saturation = clip.saturation;
                other.warmth = clip.warmth;
            }
            saveProject();
            rebuildPlaylist(currentProjectTime());
            toast("Эффект применён ко всем клипам");
        }));
        actions.addView(chip("Сброс коррекции", false, () -> {
            pushUndo();
            clip.brightness = 0f;
            clip.contrast = 1f;
            clip.saturation = 0f;
            clip.warmth = 0f;
            saveProject();
            rebuildPlaylist(currentProjectTime());
            refreshPanel();
        }));
        box.addView(actions);
        return box;
    }

    private View aspectPanel() {
        LinearLayout box = newPanel();
        addHeader(box, "Формат кадра", project.aspectLabel());
        LinearLayout chips = row();
        final String[] labels = {"9:16", "16:9", "1:1", "4:5"};
        final float[] values = {9f / 16f, 16f / 9f, 1f, 4f / 5f};
        for (int i = 0; i < values.length; i++) {
            final float aspect = values[i];
            boolean selected = Math.abs(project.aspect - aspect) < 0.02f;
            chips.addView(chip(labels[i], selected, () -> {
                pushUndo();
                project.aspect = aspect;
                previewBox.setAspectRatio(aspect);
                saveProject();
                updateChrome();
                refreshPanel();
                rebuildTextOverlays();
            }));
        }
        box.addView(chips);
        return box;
    }

    private View morePanel() {
        LinearLayout box = newPanel();
        addHeader(box, "Действия с клипом", null);
        LinearLayout chips = row();
        chips.addView(chip("Влево", false, () -> {
            moveClip(-1);
        }));
        chips.addView(chip("Вправо", false, () -> {
            moveClip(1);
        }));
        chips.addView(chip("Дублировать", false, () -> {
            duplicateClip();
        }));
        chips.addView(dangerChip("Удалить", () -> {
            deleteClip();
        }));

        LinearLayout second = row();
        second.setPadding(0, dp(6), 0, 0);
        second.addView(chip("Звук клипа в музыку", false, () -> {
            Project.Clip clip = selectedClip();
            if (clip == null || clip.uri == null || clip.image) {
                toast("Звук можно взять только из видео");
                return;
            }
            extractAudio(Uri.parse(clip.uri), clip.name);
        }));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(chips);
        HorizontalScrollView scroll2 = new HorizontalScrollView(this);
        scroll2.setHorizontalScrollBarEnabled(false);
        scroll2.addView(second);
        box.addView(scroll);
        box.addView(scroll2);
        return box;
    }

    private View trimPanel() {
        LinearLayout box = newPanel();
        addHeader(box, "Обрезка", null);
        final Project.Clip clip = selectedClip();
        LinearLayout line = row();
        line.addView(caption("Тяните жёлтые метки"));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 1, 1f);
        line.addView(new View(this), spacer);
        line.addView(chip("Начало здесь", false, () -> setTrimAtPlayhead(true)));
        line.addView(chip("Конец здесь", false, () -> setTrimAtPlayhead(false)));
        line.addView(chip("Сброс", false, () -> {
            if (clip == null) {
                return;
            }
            pushUndo();
            clip.trimStartMs = 0;
            clip.trimEndMs = Math.max(250, clip.sourceDurationMs);
            clampTextTimes(clip);
            saveProject();
            timeline.setSelectedIndex(selectedIndex);
            refreshPanel();
            seekToProjectTime(project.startOfClip(selectedIndex));
            toast("Обрезка сброшена");
        }));
        line.addView(primaryChip("Готово", () -> closePanel()));
        box.addView(line);

        LinearLayout info = row();
        info.setPadding(0, dp(4), 0, 0);
        info.addView(note(trimSummary(clip)));
        box.addView(info);

        if (clip != null && clip.image) {
            LinearLayout durations = row();
            durations.setPadding(0, dp(4), 0, 0);
            final int[] seconds = {2, 3, 5, 10};
            for (int value : seconds) {
                final long millis = value * 1000L;
                boolean selected = Math.abs(clip.trimmedDurationMs() - millis) < 60;
                durations.addView(chip(value + " с", selected, () -> {
                    pushUndo();
                    clip.sourceDurationMs = Math.max(clip.sourceDurationMs, millis);
                    clip.trimStartMs = 0;
                    clip.trimEndMs = millis;
                    clampTextTimes(clip);
                    saveProject();
                    rebuildPlaylist(project.startOfClip(selectedIndex));
                    refreshPanel();
                }));
            }
            box.addView(scrollable(durations));
        }
        return box;
    }

    private void setTrimAtPlayhead(boolean start) {
        Project.Clip clip = selectedClip();
        if (clip == null || player == null) {
            return;
        }
        long inClip = Math.max(0, positionInCurrentClip());
        long source = clip.trimStartMs + inClip;
        pushUndo();
        if (start) {
            clip.trimStartMs = Math.max(0, Math.min(source, clip.trimEndMs - 250));
        } else {
            clip.trimEndMs = Math.min(clip.sourceDurationMs,
                    Math.max(source, clip.trimStartMs + 250));
        }
        clampTextTimes(clip);
        saveProject();
        rebuildPlaylist(project.startOfClip(selectedIndex));
        refreshPanel();
    }

    private String trimSummary(Project.Clip clip) {
        if (clip == null) {
            return "";
        }
        return "Начало " + formatSeconds(clip.trimStartMs)
                + "   ·   Конец " + formatSeconds(clip.trimEndMs)
                + "   ·   В ролике " + formatSeconds(clip.trimmedDurationMs());
    }

    private View speedPanel() {
        final Project.Clip clip = selectedClip();
        LinearLayout box = newPanel();
        addHeader(box, "Скорость", null);
        final TextView value = new TextView(this);
        value.setTextColor(getColor(R.color.text));
        value.setTextSize(11f);
        value.setText(String.format(Locale.US, "%.2fx", clip.speed));

        LinearLayout line = row();
        line.addView(value);
        line.addView(panelSlider(375, (int) (clip.speed * 100) - 25,
                new SimpleSeekListener() {
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        pushUndo();
                    }

                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        float speed = (progress + 25) / 100f;
                        clip.speed = speed;
                        value.setText(String.format(Locale.US, "%.2fx", speed));
                        timeline.setProject(project);
                        applyPlaybackParams();
                        updateChrome();
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        saveProject();
                        refreshPanel();
                    }
                }));
        box.addView(line);

        LinearLayout presets = row();
        presets.setPadding(0, dp(2), 0, 0);
        final float[] values = {0.5f, 1f, 1.5f, 2f, 3f, 4f};
        for (float preset : values) {
            final float speed = preset;
            String label = (speed == Math.round(speed) ? String.valueOf((int) speed)
                    : String.valueOf(speed)) + "x";
            presets.addView(chip(label, Math.abs(clip.speed - speed) < 0.01f, () -> {
                pushUndo();
                clip.speed = speed;
                saveProject();
                timeline.setProject(project);
                applyPlaybackParams();
                updateChrome();
                refreshPanel();
            }));
        }
        box.addView(scrollable(presets));

        TextView impact = note("Длительность: " + formatSeconds(clip.trimmedDurationMs())
                + "  →  " + formatSeconds(clip.outputDurationMs()));
        impact.setPadding(0, dp(4), 0, 0);
        box.addView(impact);
        return box;
    }

    private View volumePanel() {
        final Project.Clip clip = selectedClip();
        LinearLayout box = newPanel();
        addHeader(box, "Звук клипа", null);
        final TextView value = new TextView(this);
        value.setTextColor(getColor(R.color.text));
        value.setTextSize(11f);
        value.setText(Math.round(clip.volume * 100) + "%");

        LinearLayout line = row();
        line.addView(value);
        line.addView(panelSlider(200, Math.round(clip.volume * 100),
                new SimpleSeekListener() {
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        pushUndo();
                    }

                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        if (progress > 0 && project.muteOriginal) {
                            project.muteOriginal = false;
                        }
                        clip.volume = progress / 100f;
                        value.setText(progress + "%");
                        applyClipVolume();
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        saveProject();
                        updateChrome();
                        refreshPanel();
                    }
                }));
        box.addView(line);

        LinearLayout chips = row();
        chips.setPadding(0, dp(2), 0, 0);
        chips.addView(chip("Без звука", clip.volume < 0.01f, () -> {
            pushUndo();
            clip.volume = 0f;
            saveProject();
            applyClipVolume();
            refreshPanel();
        }));
        chips.addView(chip("100%", Math.abs(clip.volume - 1f) < 0.01f, () -> {
            pushUndo();
            clip.volume = 1f;
            saveProject();
            applyClipVolume();
            refreshPanel();
        }));
        chips.addView(chip("150%", Math.abs(clip.volume - 1.5f) < 0.01f, () -> {
            pushUndo();
            clip.volume = 1.5f;
            saveProject();
            applyClipVolume();
            refreshPanel();
        }));
        box.addView(scrollable(chips));

        LinearLayout master = row();
        master.setPadding(0, dp(4), 0, 0);
        master.addView(chip(project.muteOriginal
                        ? "Оригинальный звук: выкл"
                        : "Оригинальный звук: вкл",
                project.muteOriginal, () -> {
                    pushUndo();
                    project.muteOriginal = !project.muteOriginal;
                    saveProject();
                    applyClipVolume();
                    refreshPanel();
                    toast(project.muteOriginal
                            ? "Звук видео отключён, музыка играет"
                            : "Звук видео включён");
                }));
        box.addView(scrollable(master));
        return box;
    }

    private View scrollable(View content) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content);
        return scroll;
    }

    private View exportPanel() {
        LinearLayout box = newPanel();
        addHeader(box, "Экспорт", null);
        LinearLayout chips = row();
        chips.addView(chip("720p", exportShortSide == 720, () -> {
            exportShortSide = 720;
            refreshPanel();
        }));
        chips.addView(chip("1080p", exportShortSide == 1080, () -> {
            exportShortSide = 1080;
            refreshPanel();
        }));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 1, 1f);
        chips.addView(new View(this), spacer);
        chips.addView(primaryChip("Экспорт", () -> startExport(exportShortSide)));
        box.addView(chips);

        String audio = project.muteOriginal ? "без звука видео"
                : (project.musicUri == null ? "звук видео" : "видео + музыка");
        TextView summary = note(project.clips.size() + " " + clipsWord(project.clips.size())
                + "  ·  "
                + formatDuration(project.durationMs()) + "  ·  " + project.aspectLabel()
                + "  ·  " + audio);
        summary.setPadding(0, dp(4), 0, 0);
        box.addView(summary);
        return box;
    }

    private View musicPanel() {
        View view = LayoutInflater.from(this).inflate(R.layout.panel_music, panelHost, false);
        addHeader(view, "Музыка", project.musicUri == null ? "нет дорожки" : "включена");
        TextView name = view.findViewById(R.id.musicName);
        name.setText(project.musicUri == null ? "Дорожка не выбрана"
                : (project.musicName == null ? "Дорожка" : project.musicName));

        view.findViewById(R.id.btnMusicVideo).setOnClickListener(v -> pickVideoForAudio());
        view.findViewById(R.id.btnMusicFile).setOnClickListener(v -> pickMusic());
        view.findViewById(R.id.btnMusicClip).setOnClickListener(v -> {
            Project.Clip clip = selectedClip();
            if (clip == null || clip.image) {
                toast("Сначала выберите видеоклип");
                return;
            }
            extractAudio(Uri.parse(clip.uri), clip.name);
        });
        View remove = view.findViewById(R.id.btnMusicRemove);
        remove.setVisibility(project.musicUri == null ? View.GONE : View.VISIBLE);
        remove.setOnClickListener(v -> {
            pushUndo();
            project.musicUri = null;
            project.musicName = null;
            saveProject();
            prepareMusic();
            refreshPanel();
            toast("Музыка убрана");
        });

        LinearLayout masterRow = row();
        masterRow.setPadding(0, dp(6), 0, 0);
        masterRow.addView(chip(project.muteOriginal
                ? "Звук видео: выключен"
                : "Звук видео: включён", project.muteOriginal, () -> {
            pushUndo();
            project.muteOriginal = !project.muteOriginal;
            saveProject();
            applyClipVolume();
            refreshPanel();
        }));
        ((LinearLayout) view).addView(masterRow);

        final SeekBar volume = view.findViewById(R.id.musicVolume);
        final TextView volumeValue = view.findViewById(R.id.musicVolumeValue);
        volume.setProgress(Math.round(project.musicVolume * 100));
        volumeValue.setText(Math.round(project.musicVolume * 100) + "%");
        volume.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pushUndo();
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(progress + "%");
                if (fromUser) {
                    project.musicVolume = progress / 100f;
                    if (musicPlayer != null) {
                        musicPlayer.setVolume(Math.max(0f, Math.min(1f, project.musicVolume)));
                    }
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                project.musicVolume = seekBar.getProgress() / 100f;
                saveProject();
            }
        });
        return view;
    }

    private View textPanel() {
        final Project.Clip clip = selectedClip();
        View view = LayoutInflater.from(this).inflate(R.layout.panel_text, panelHost, false);
        addHeader(view, "Текст", null);
        final android.widget.EditText input = view.findViewById(R.id.textInput);
        final SeekBar sizeSeek = view.findViewById(R.id.sizeSeek);
        final TextView sizeValue = view.findViewById(R.id.sizeValue);
        final SeekBar startSeek = view.findViewById(R.id.textStartSeek);
        final SeekBar endSeek = view.findViewById(R.id.textEndSeek);
        final TextView timeLabel = view.findViewById(R.id.textTimeLabel);
        final LinearLayout colorRow = view.findViewById(R.id.colorRow);
        final LinearLayout positionRow = view.findViewById(R.id.positionRow);

        final int duration = (int) Math.max(400, clip.trimmedDurationMs());
        startSeek.setMax(duration);
        endSeek.setMax(duration);
        startSeek.setProgress(0);
        endSeek.setProgress(duration);
        sizeSeek.setProgress(30);
        sizeValue.setText("30");
        pendingTextColor = 0xFFFFFFFF;
        pendingTextPosition = 1;

        final Runnable updateTime = () -> timeLabel.setText(
                formatSeconds(startSeek.getProgress()) + "\n" + formatSeconds(endSeek.getProgress()));
        updateTime.run();

        sizeSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeValue.setText(String.valueOf(progress));
            }
        });
        startSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress > endSeek.getProgress() - 250) {
                    seekBar.setProgress(Math.max(0, endSeek.getProgress() - 250));
                }
                updateTime.run();
            }
        });
        endSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < startSeek.getProgress() + 250) {
                    seekBar.setProgress(Math.min(duration, startSeek.getProgress() + 250));
                }
                updateTime.run();
            }
        });

        final int[] colors = {0xFFFFFFFF, 0xFF000000, 0xFF00E5A0, 0xFFFFD400,
                0xFFFF4D5E, 0xFF7C6CFF};
        buildColorChips(colorRow, colors);

        positionChips(positionRow, true);

        view.findViewById(R.id.btnTextAdd).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                toast("Введите текст");
                return;
            }
            pushUndo();
            Project.TextItem item = new Project.TextItem();
            item.text = text;
            item.color = pendingTextColor;
            item.size = 0.025f + (sizeSeek.getProgress() / 100f) * 0.10f;
            item.position = pendingTextPosition;
            item.startMs = startSeek.getProgress();
            item.endMs = Math.max(item.startMs + 250, endSeek.getProgress());
            clip.texts.add(item);
            saveProject();
            closePanel();
            rebuildTextOverlays();
            updateChrome();
            toast("Текст добавлен");
        });
        return view;
    }

    private void buildColorChips(final LinearLayout row, final int[] colors) {
        row.removeAllViews();
        for (final int color : colors) {
            View chip = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(color);
            background.setStroke(dp(pendingTextColor == color ? 2 : 1),
                    pendingTextColor == color ? 0xFF00E5A0 : 0xFF3A3A44);
            chip.setBackground(background);
            chip.setOnClickListener(v -> {
                pendingTextColor = color;
                buildColorChips(row, colors);
            });
            row.addView(chip);
        }
    }

    private void positionChips(final LinearLayout container, boolean rebuild) {
        final String[] labels = {"Сверху", "Центр", "Снизу"};
        container.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int position = i;
            container.addView(chip(labels[i], pendingTextPosition == position, () -> {
                pendingTextPosition = position;
                positionChips(container, true);
            }));
        }
    }

    // ------------------------------------------------------------------
    // Preview: videos through ExoPlayer, photos through our own still layer
    // ------------------------------------------------------------------

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(false);

        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                int playerIndex = player.getCurrentMediaItemIndex();
                if (playerIndex >= 0 && playerIndex < playerClipIndexes.size()) {
                    int clipIndex = playerClipIndexes.get(playerIndex);
                    photoMode = false;
                    photoPlaying = false;
                    updateImageLayer(-1);
                    selectedIndex = clipIndex;
                    timeline.setSelectedIndex(clipIndex);
                    applyPlaybackParams();
                    rebuildTextOverlays();
                    updateChrome();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                updatePlayState();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayState();
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // A broken source should not kill the whole preview.
                toast("Не удалось воспроизвести клип");
            }
        });
    }

    private void setupMusicPlayer() {
        musicPlayer = new ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, false)
                .build();
        musicPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        musicPlayer.setVolume(Math.max(0f, Math.min(1f, project.musicVolume)));
    }

    private void prepareMusic() {
        if (musicPlayer == null) {
            return;
        }
        try {
            musicPlayer.stop();
            musicPlayer.clearMediaItems();
            if (project.musicUri == null || project.musicUri.isEmpty()) {
                return;
            }
            musicPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(project.musicUri)));
            musicPlayer.setVolume(Math.max(0f, Math.min(1f, project.musicVolume)));
            musicPlayer.prepare();
        } catch (Throwable ignored) {
        }
    }

    private void syncMusicPosition(long projectTimeMs) {
        if (musicPlayer == null || musicPlayer.getMediaItemCount() == 0) {
            return;
        }
        try {
            long duration = musicPlayer.getDuration();
            long position = projectTimeMs;
            if (duration > 0) {
                position = projectTimeMs % duration;
            }
            musicPlayer.seekTo(Math.max(0, position));
        } catch (Throwable ignored) {
        }
    }

    private void updateMusicPlayback() {
        if (musicPlayer == null || musicPlayer.getMediaItemCount() == 0) {
            return;
        }
        try {
            musicPlayer.setVolume(Math.max(0f, Math.min(1f, project.musicVolume)));
            boolean playing = isPlaying();
            if (playing) {
                if (musicPlayer.getPlaybackState() == Player.STATE_IDLE) {
                    musicPlayer.prepare();
                }
                if (!musicPlayer.isPlaying()) {
                    musicPlayer.play();
                }
            } else if (musicPlayer.isPlaying()) {
                musicPlayer.pause();
            }
        } catch (Throwable ignored) {
        }
    }

    private void setupTimeline() {
        timeline.setListener(new TimelineView.Listener() {
            @Override
            public void onSeek(long timeMs) {
                seekToProjectTime(timeMs, true);
            }

            @Override
            public void onScrub(long timeMs) {
                seekToProjectTime(timeMs, false);
            }

            @Override
            public void onClipSelected(int index) {
                selectedIndex = index;
                timeline.setSelectedIndex(index);
                updateChrome();
                rebuildTextOverlays();
            }

            @Override
            public void onTrim(int index, long trimStartMs, long trimEndMs, boolean finished) {
                handleTrim(index, trimStartMs, trimEndMs, finished);
            }

            @Override
            public void onTrimPreview(int index, long sourceMs) {
                previewSourceFrame(index, sourceMs);
            }

            @Override
            public void onMusicTap() {
                togglePanel(P_MUSIC);
            }

            @Override
            public void onReorder(int fromIndex, int toIndex) {
                reorderClip(fromIndex, toIndex);
            }
        });
    }

    private Project.Clip selectedClip() {
        if (project == null || selectedIndex < 0 || selectedIndex >= project.clips.size()) {
            return null;
        }
        return project.clips.get(selectedIndex);
    }

    /** Clip index currently shown in the preview, -1 when nothing is selected. */
    private int currentClipIndex() {
        if (photoMode) {
            return photoClipIndex;
        }
        if (player == null) {
            return -1;
        }
        int playerIndex = player.getCurrentMediaItemIndex();
        if (playerIndex < 0 || playerIndex >= playerClipIndexes.size()) {
            return -1;
        }
        return playerClipIndexes.get(playerIndex);
    }

    private int playerIndexForClip(int clipIndex) {
        return playerClipIndexes.indexOf(clipIndex);
    }

    private long positionInCurrentClip() {
        int clipIndex = currentClipIndex();
        if (clipIndex < 0) {
            return 0;
        }
        if (photoMode) {
            return Math.max(0, photoPositionMs - project.startOfClip(clipIndex));
        }
        return Math.max(0, player.getCurrentPosition());
    }

    private void rebuildPlaylist(long seekMs) {
        if (player == null) {
            return;
        }
        playerClipIndexes.clear();
        loadedTrimStarts = new long[project.clips.size()];
        player.stop();
        player.clearMediaItems();
        for (int i = 0; i < project.clips.size(); i++) {
            Project.Clip clip = project.clips.get(i);
            loadedTrimStarts[i] = clip.trimStartMs;
            if (clip.uri == null || clip.image) {
                continue;
            }
            playerClipIndexes.add(i);
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
        long target = Math.max(0, Math.min(seekMs, project.durationMs()));
        int clipIndex = project.clipIndexAt(target);
        if (clipIndex >= 0) {
            Project.Clip clip = project.clips.get(clipIndex);
            try {
                player.setVideoEffects(FilterPresets.effectsFor(clip));
                player.setPlaybackParameters(new PlaybackParameters(Math.max(0.1f, clip.speed)));
            } catch (Throwable ignored) {
            }
        }
        if (player.getMediaItemCount() > 0) {
            player.prepare();
        }
        timeline.setProject(project);
        timeline.setSelectedIndex(selectedIndex);
        seekToProjectTime(target, true);
        refreshHistoryButtons();
    }

    private void seekToProjectTime(long timeMs) {
        seekToProjectTime(timeMs, true);
    }

    private void seekToProjectTime(long timeMs, boolean rebuildOverlay) {
        if (project.clips.isEmpty()) {
            return;
        }
        long target = Math.max(0, Math.min(timeMs, project.durationMs()));
        int clipIndex = project.clipIndexAt(target);
        if (clipIndex < 0) {
            return;
        }
        Project.Clip clip = project.clips.get(clipIndex);
        selectedIndex = clipIndex;
        timeline.setSelectedIndex(clipIndex);
        timeline.setPosition(target);
        photoPositionMs = target;

        if (clip.image) {
            enterPhotoMode(clipIndex, target);
        } else {
            photoMode = false;
            updateImageLayer(-1);
            int playerIndex = playerIndexForClip(clipIndex);
            if (playerIndex >= 0) {
                long position = Math.round((target - project.startOfClip(clipIndex))
                        * Math.max(0.1f, clip.speed));
                player.seekTo(playerIndex, Math.max(0, position));
            }
        }
        syncMusicPosition(target);
        if (rebuildOverlay) {
            rebuildTextOverlays();
        }
        updateTimeLabels();
        updateChrome();
        updatePlayState();
    }

    private void enterPhotoMode(int clipIndex, long projectTimeMs) {
        photoMode = true;
        photoClipIndex = clipIndex;
        photoPositionMs = projectTimeMs;
        photoLastTickMs = System.currentTimeMillis();
        if (player != null && player.isPlaying()) {
            player.pause();
        }
        showPhoto(project.clips.get(clipIndex));
        updateImageLayer(clipIndex);
    }

    /** Decodes a photo for the preview: app-storage copies, MediaStore or picker URIs. */
    private Bitmap loadPhotoBitmap(String value, int targetWidth) {
        Uri uri = Uri.parse(value);
        if ("file".equals(uri.getScheme())) {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(uri.getPath(), bounds);
                int sample = 1;
                int width = Math.max(1, bounds.outWidth);
                while (width / (sample * 2) >= targetWidth) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                return BitmapFactory.decodeFile(uri.getPath(), options);
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            return getContentResolver().loadThumbnail(
                    uri, new Size(targetWidth, targetWidth), null);
        } catch (Throwable ignored) {
        }
        try {
            return MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void showPhoto(Project.Clip clip) {
        if (clip == null || clip.uri == null) {
            return;
        }
        Bitmap cached = photoCache.get(clip.uri);
        if (cached != null && !cached.isRecycled()) {
            imagePreview.setImageBitmap(cached);
            imagePreview.setVisibility(View.VISIBLE);
            return;
        }
        final String key = clip.uri;
        final int targetWidth = Math.max(480, previewBox.getWidth());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap result = loadPhotoBitmap(key, targetWidth);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result == null) {
                            return;
                        }
                        photoCache.put(key, result);
                        int clipIndex = currentClipIndex();
                        if (clipIndex >= 0 && clipIndex < project.clips.size()
                                && project.clips.get(clipIndex).image) {
                            imagePreview.setImageBitmap(result);
                            imagePreview.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private void updateImageLayer(int clipIndex) {
        if (imagePreview == null) {
            return;
        }
        boolean photo = clipIndex >= 0 && clipIndex < project.clips.size()
                && project.clips.get(clipIndex).image;
        if (!photo) {
            imagePreview.setVisibility(View.GONE);
            imagePreview.setImageDrawable(null);
        }
    }

    private void previewSourceFrame(int index, long sourceMs) {
        if (index < 0 || index >= project.clips.size()) {
            return;
        }
        if (project.clips.get(index).image) {
            return;
        }
        long loadedStart = index < loadedTrimStarts.length ? loadedTrimStarts[index] : 0;
        long position = Math.max(0, sourceMs - loadedStart);
        int playerIndex = playerIndexForClip(index);
        if (playerIndex >= 0) {
            player.seekTo(playerIndex, position);
        }
    }

    private void handleTrim(int index, long startMs, long endMs, boolean finished) {
        if (index < 0 || index >= project.clips.size()) {
            return;
        }
        Project.Clip clip = project.clips.get(index);
        if (!trimGesture) {
            pushUndo();
            trimGesture = true;
            pause();
        }
        clip.trimStartMs = startMs;
        clip.trimEndMs = endMs;
        clampTextTimes(clip);
        selectedIndex = index;
        timeline.setProject(project);
        if (finished) {
            trimGesture = false;
            saveProject();
            rebuildPlaylist(project.startOfClip(index));
        } else {
            updateChrome();
        }
    }

    private void applyPlaybackParams() {
        if (player == null) {
            return;
        }
        int clipIndex = currentClipIndex();
        if (clipIndex < 0 || clipIndex >= project.clips.size()) {
            return;
        }
        Project.Clip clip = project.clips.get(clipIndex);
        try {
            player.setPlaybackParameters(new PlaybackParameters(Math.max(0.1f, clip.speed)));
            applyClipVolume();
        } catch (Throwable ignored) {
        }
    }

    private void applyClipVolume() {
        if (player == null || project == null) {
            return;
        }
        int clipIndex = currentClipIndex();
        Project.Clip clip = (clipIndex >= 0 && clipIndex < project.clips.size())
                ? project.clips.get(clipIndex) : selectedClip();
        float volume = clip == null ? 1f : clip.volume;
        if (project.muteOriginal || volume <= 0.01f) {
            volume = 0f;
        }
        try {
            player.setVolume(Math.max(0f, Math.min(1f, volume)));
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap copyBitmap(Bitmap source) {
        if (source == null) {
            return null;
        }
        try {
            Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
            return copy == null ? source : copy;
        } catch (Throwable t) {
            return source;
        }
    }

    private boolean isPlaying() {
        if (photoMode) {
            return photoPlaying;
        }
        return player != null && player.isPlaying();
    }

    private void play() {
        if (project.clips.isEmpty()) {
            pickMedia();
            return;
        }
        int clipIndex = currentClipIndex();
        if (clipIndex < 0) {
            seekToProjectTime(0, true);
            clipIndex = currentClipIndex();
        }
        if (clipIndex < 0) {
            return;
        }
        Project.Clip clip = project.clips.get(clipIndex);
        if (clip.image) {
            if (photoPositionMs >= project.startOfClip(clipIndex) + clip.outputDurationMs() - 20) {
                photoPositionMs = project.startOfClip(clipIndex);
            }
            photoMode = true;
            photoClipIndex = clipIndex;
            photoPlaying = true;
            photoLastTickMs = System.currentTimeMillis();
        } else {
            photoPlaying = false;
            int playerIndex = playerIndexForClip(clipIndex);
            if (playerIndex >= 0) {
                if (player.getPlaybackState() == Player.STATE_ENDED) {
                    seekToProjectTime(0, true);
                }
                player.play();
            }
        }
        updatePlayState();
    }

    private void pause() {
        photoPlaying = false;
        if (player != null) {
            player.pause();
        }
        updatePlayState();
    }

    private void togglePlay() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    private void updatePlayState() {
        boolean playing = isPlaying();
        boolean empty = project == null || project.clips.isEmpty();
        centerPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        centerPlay.setVisibility(playing || empty ? View.GONE : View.VISIBLE);
        emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        updateMusicPlayback();
    }

    private void tick() {
        if (project == null || project.clips.isEmpty()) {
            return;
        }
        long output;
        int clipIndex = currentClipIndex();
        if (clipIndex < 0) {
            return;
        }
        Project.Clip clip = project.clips.get(clipIndex);

        if (photoMode) {
            long now = System.currentTimeMillis();
            if (photoPlaying) {
                photoPositionMs += Math.max(0, now - photoLastTickMs);
            }
            photoLastTickMs = now;
            long clipStart = project.startOfClip(clipIndex);
            long clipEnd = clipStart + clip.outputDurationMs();
            if (photoPlaying && photoPositionMs >= clipEnd - 10) {
                boolean continuePlaying = true;
                photoPlaying = false;
                photoMode = false;
                long next = Math.min(clipEnd + 5, project.durationMs());
                seekToProjectTime(next, true);
                if (continuePlaying) {
                    play();
                }
                return;
            }
            output = Math.max(clipStart, Math.min(photoPositionMs, clipEnd));
            updateTextVisibility(output - clipStart);
        } else {
            long position = Math.max(0, player.getCurrentPosition());
            output = project.startOfClip(clipIndex)
                    + Math.round(position / Math.max(0.1f, clip.speed));
            output = Math.max(0, Math.min(output, project.durationMs()));
            updateTextVisibility(position);
        }

        timeline.setPosition(output);
        updateTimeLabels();
    }

    private long currentProjectTime() {
        if (project == null || project.clips.isEmpty()) {
            return 0;
        }
        int clipIndex = currentClipIndex();
        if (clipIndex < 0) {
            return 0;
        }
        if (photoMode) {
            return Math.max(0, photoPositionMs);
        }
        Project.Clip clip = project.clips.get(clipIndex);
        long position = Math.max(0, player.getCurrentPosition());
        return project.startOfClip(clipIndex) + Math.round(position / Math.max(0.1f, clip.speed));
    }

    private void updateTextVisibility(long positionInClip) {
        for (TextView view : textViews) {
            Project.TextItem item = (Project.TextItem) view.getTag();
            if (item == null) {
                continue;
            }
            boolean visible = positionInClip >= item.startMs && positionInClip <= item.endMs;
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateTimeLabels() {
        if (project == null) {
            return;
        }
        long position = Math.max(0, currentProjectTime());
        timeText.setText(formatDuration(position) + "  /  " + formatDuration(project.durationMs()));
    }

    // ------------------------------------------------------------------
    // Text overlays in the preview
    // ------------------------------------------------------------------

    private void rebuildTextOverlays() {
        if (textOverlay == null) {
            return;
        }
        textOverlay.removeAllViews();
        textViews.clear();
        int index = currentClipIndex();
        if (index < 0) {
            index = selectedIndex;
        }
        if (index < 0 || index >= project.clips.size()) {
            return;
        }
        Project.Clip clip = project.clips.get(index);
        int height = previewBox.getHeight();
        if (height <= 0) {
            height = dp(220);
        }
        for (final Project.TextItem item : clip.texts) {
            if (item.text == null || item.text.trim().isEmpty()) {
                continue;
            }
            TextView view = new TextView(this);
            view.setText(item.text);
            view.setTextColor(item.color);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(9), height * item.size));
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setGravity(Gravity.CENTER);
            view.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
            view.setPadding(dp(8), dp(2), dp(8), dp(2));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            if (item.position == 0) {
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.topMargin = dp(14);
            } else if (item.position == 2) {
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(14);
            } else {
                lp.gravity = Gravity.CENTER;
            }
            lp.leftMargin = dp(10);
            lp.rightMargin = dp(10);
            view.setLayoutParams(lp);
            view.setTag(item);
            view.setVisibility(View.GONE);
            view.setOnLongClickListener(v -> {
                confirmDeleteText(item);
                return true;
            });
            view.setOnTouchListener(new View.OnTouchListener() {
                private float startY;
                private float startRawY;
                private int startPosition;
                private boolean moved;

                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            startY = v.getY();
                            startRawY = event.getRawY();
                            startPosition = item.position;
                            moved = false;
                            return false;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            float dy = event.getRawY() - startRawY;
                            if (Math.abs(dy) > dp(6)) {
                                moved = true;
                            }
                            if (moved) {
                                float target = startY + dy;
                                float height = Math.max(1, textOverlay.getHeight());
                                v.setY(Math.max(0, Math.min(height - v.getHeight(), target)));
                            }
                            return moved;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL: {
                            if (!moved) {
                                return false;
                            }
                            float center = v.getY() + v.getHeight() / 2f;
                            float height = Math.max(1, textOverlay.getHeight());
                            int newPosition = center < height * 0.34f ? 0
                                    : center > height * 0.66f ? 2 : 1;
                            if (newPosition != startPosition) {
                                pushUndo();
                                item.position = newPosition;
                                saveProject();
                                rebuildTextOverlays();
                                toast(newPosition == 0 ? "Текст сверху"
                                        : newPosition == 2 ? "Текст снизу" : "Текст по центру");
                            } else {
                                rebuildTextOverlays();
                            }
                            return true;
                        }
                        default:
                            return false;
                    }
                }
            });
            textOverlay.addView(view);
            textViews.add(view);
        }
        updateTextVisibility(Math.max(0, player == null ? 0 : player.getCurrentPosition()));
    }

    private void confirmDeleteText(final Project.TextItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить текст?")
                .setMessage(item.text)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    Project.Clip clip = selectedClip();
                    if (clip != null) {
                        pushUndo();
                        clip.texts.remove(item);
                        saveProject();
                        rebuildTextOverlays();
                        updateChrome();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void clampTextTimes(Project.Clip clip) {
        long max = clip.trimmedDurationMs();
        for (Project.TextItem item : clip.texts) {
            if (item.startMs > max) {
                item.startMs = max;
            }
            if (item.endMs > max) {
                item.endMs = max;
            }
            if (item.endMs < item.startMs) {
                item.endMs = item.startMs;
            }
        }
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /** System photo picker: Google Photos, gallery and other cloud providers. */
    private void pickMedia() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"image/*", "video/*"});
                intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                        Math.max(1, MediaStore.getPickImagesMaxLimit()));
                startActivityForResult(intent, REQ_PICK_MEDIA);
                return;
            }
            pickFiles();
        } catch (Throwable t) {
            pickFiles();
        }
    }

    /** Document picker fallback: works everywhere and also reaches Google Photos files. */
    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_PICK_FILES);
        } catch (Throwable t) {
            toast("Не удалось открыть проводник");
        }
    }

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
        addPickedUris(data, false);
    }

    private void handlePickedMedia(Intent data) {
        addPickedUris(data, true);
    }

    private void addPickedUris(Intent data, boolean allowPhotos) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        pushUndo();
        int firstIndex = project.clips.size();
        int added = 0;
        int photos = 0;
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            String mime = null;
            try {
                mime = getContentResolver().getType(uri);
            } catch (Throwable ignored) {
            }
            if (mime == null) {
                mime = guessMime(uri);
            }
            boolean image;
            if (mime != null) {
                if (mime.startsWith("image/")) {
                    image = allowPhotos;
                    if (!image) {
                        continue;
                    }
                } else if (mime.startsWith("video/")) {
                    image = false;
                } else {
                    continue;
                }
            } else {
                image = allowPhotos && readDuration(uri) <= 0;
            }
            String name = readName(uri);
            String stored = uri.toString();
            if (image) {
                String local = copyPhotoToAppStorage(uri, name);
                if (local != null) {
                    stored = local;
                }
            }
            long duration = image ? Project.PHOTO_DURATION_MS : readDuration(uri);
            if (!image && duration < 400) {
                continue;
            }
            if (image && duration <= 0) {
                duration = Project.PHOTO_DURATION_MS;
            }
            Project.Clip clip = new Project.Clip();
            clip.uri = stored;
            clip.name = name;
            clip.image = image;
            clip.sourceDurationMs = duration;
            clip.trimStartMs = 0;
            clip.trimEndMs = duration;
            project.clips.add(clip);
            added++;
            if (image) {
                photos++;
            }
        }
        if (added == 0) {
            toast("Не удалось прочитать выбранные файлы");
            return;
        }
        saveProject();
        selectedIndex = firstIndex;
        rebuildPlaylist(project.startOfClip(firstIndex));
        String what = photos == 0 ? "видео" : (photos == added ? "фото" : "медиа");
        toast(added + " " + what + " добавлено");
    }

    /** Cheap mime guess for providers that do not answer getType() (cloud photos). */
    private String guessMime(Uri uri) {
        String value = uri.getLastPathSegment();
        if (value == null) {
            return null;
        }
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) {
            return null;
        }
        String ext = value.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "jpg":
            case "jpeg":
            case "png":
            case "webp":
            case "heic":
            case "heif":
            case "bmp":
            case "gif":
            case "avif":
                return "image/" + ext;
            case "mp4":
            case "m4v":
            case "mov":
            case "mkv":
            case "webm":
            case "3gp":
            case "avi":
                return "video/" + ext;
            default:
                return null;
        }
    }

    /**
     * Photos from the system picker (especially Google Photos) only grant temporary read
     * access, so the bytes are copied into app storage. Keeps originals intact for export.
     */
    private String copyPhotoToAppStorage(Uri uri, String displayName) {
        File dir = new File(getFilesDir(), "photos");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String ext = "jpg";
        String lower = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            String candidate = lower.substring(dot + 1).replaceAll("[^a-z0-9]", "");
            if (candidate.length() >= 2 && candidate.length() <= 5) {
                ext = candidate;
            }
        }
        File output = new File(dir, "photo_" + System.currentTimeMillis()
                + "_" + Math.abs(uri.hashCode()) + "." + ext);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            out = new FileOutputStream(output);
            byte[] buffer = new byte[64 * 1024];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            if (total <= 0) {
                output.delete();
                return null;
            }
            return Uri.fromFile(output).toString();
        } catch (Throwable t) {
            output.delete();
            return null;
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
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
        String last = uri.getLastPathSegment();
        return last == null ? "Клип" : last;
    }

    // ------------------------------------------------------------------
    // Editing operations
    // ------------------------------------------------------------------

    private void splitClip() {
        if (project.clips.isEmpty()) {
            toast("Добавьте видео");
            return;
        }
        int index = currentClipIndex();
        if (index < 0) {
            index = selectedIndex;
        }
        if (index < 0 || index >= project.clips.size()) {
            toast("Выберите клип");
            return;
        }
        Project.Clip clip = project.clips.get(index);
        long position = Math.max(0, positionInCurrentClip());
        long cut = clip.trimStartMs + position;
        if (cut <= clip.trimStartMs + 250 || cut >= clip.trimEndMs - 250) {
            toast("Переместите плейхед внутрь клипа");
            return;
        }
        pushUndo();
        playerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
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
            Project.TextItem copy = copyText(item);
            if (start >= relative) {
                copy.startMs = start - relative;
                copy.endMs = end - relative;
                moving.add(copy);
            } else {
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
        rebuildPlaylist(project.startOfClip(selectedIndex));
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

    private void reorderClip(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= project.clips.size()
                || toIndex < 0 || toIndex >= project.clips.size()
                || fromIndex == toIndex) {
            return;
        }
        pushUndo();
        Project.Clip clip = project.clips.remove(fromIndex);
        project.clips.add(toIndex, clip);
        selectedIndex = toIndex;
        saveProject();
        rebuildPlaylist(project.startOfClip(toIndex));
        toast("Клип перемещён");
    }

    private void deleteClip() {
        int index = selectedIndex;
        if (index < 0 || index >= project.clips.size()) {
            toast("Выберите клип");
            return;
        }
        pushUndo();
        long time = project.startOfClip(index);
        project.clips.remove(index);
        saveProject();
        if (project.clips.isEmpty()) {
            selectedIndex = -1;
            player.stop();
            player.clearMediaItems();
            closePanel();
            updateImageLayer(-1);
            timeline.setProject(project);
            timeline.setSelectedIndex(-1);
            updateChrome();
            rebuildTextOverlays();
            updatePlayState();
            updateTimeLabels();
            toast("Клип удалён");
            return;
        }
        selectedIndex = Math.min(index, project.clips.size() - 1);
        updateImageLayer(selectedIndex);
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
        pushUndo();
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
        pushUndo();
        Project.Clip copy = Project.Clip.fromJson(project.clips.get(index).toJson());
        copy.id = java.util.UUID.randomUUID().toString();
        project.clips.add(index + 1, copy);
        saveProject();
        selectedIndex = index + 1;
        rebuildPlaylist(project.startOfClip(selectedIndex));
        toast("Клип дублирован");
    }

    private void renameProject() {
        final android.widget.EditText input = new android.widget.EditText(this);
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
                            saveProject();
                            updateChrome();
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Music / audio extraction
    // ------------------------------------------------------------------

    private void pickMusic() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        try {
            startActivityForResult(intent, REQ_MUSIC);
        } catch (Throwable t) {
            toast("Не удалось выбрать музыку");
        }
    }

    private void pickVideoForAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        try {
            startActivityForResult(intent, REQ_AUDIO_VIDEO);
        } catch (Throwable t) {
            toast("Не удалось открыть проводник");
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
        pushUndo();
        project.musicUri = uri.toString();
        project.musicName = readName(uri);
        saveProject();
        prepareMusic();
        refreshPanel();
        toast("Музыка: " + project.musicName);
    }

    private void extractAudio(final Uri uri, String label) {
        File dir = new File(getFilesDir(), "audio");
        final File output = new File(dir, "audio_" + System.currentTimeMillis() + ".m4a");
        toast("Извлекаю звук…");
        AudioExtractor.extract(this, uri.toString(), output, new AudioExtractor.Callback() {
            @Override
            public void onDone(File file) {
                pushUndo();
                project.musicUri = Uri.fromFile(file).toString();
                String name = file.getName();
                project.musicName = label == null ? name : label + " · звук";
                saveProject();
                prepareMusic();
                refreshPanel();
                toast("Звук добавлен как музыка");
            }

            @Override
            public void onError(String message) {
                toast("Не удалось извлечь звук: " + message);
            }
        });
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private void startExport(int shortSide) {
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
        exporter.export(project, shortSide, output, new VideoExporter.Callback() {
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
                .setTitle("Готово")
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
        if (progressDialog.getWindow() != null) {
            progressDialog.getWindow().setLayout(dp(300),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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
    // State, undo/redo, chrome
    // ------------------------------------------------------------------

    private void saveProject() {
        if (project != null) {
            ProjectStore.save(this, project);
        }
    }

    private void pushUndo() {
        try {
            undoStack.push(project.toJson().toString());
            while (undoStack.size() > 40) {
                undoStack.removeLast();
            }
            redoStack.clear();
            refreshHistoryButtons();
        } catch (Throwable ignored) {
        }
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            toast("Отменить нечего");
            return;
        }
        try {
            redoStack.push(project.toJson().toString());
            restore(undoStack.pop());
        } catch (Throwable ignored) {
        }
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            toast("Повторить нечего");
            return;
        }
        try {
            undoStack.push(project.toJson().toString());
            restore(redoStack.pop());
        } catch (Throwable ignored) {
        }
    }

    private void restore(String json) {
        try {
            Project restored = Project.fromJson(new JSONObject(json));
            project = restored;
        } catch (Throwable t) {
            toast("Не удалось восстановить состояние");
            return;
        }
        if (project.clips.isEmpty()) {
            selectedIndex = -1;
        } else {
            selectedIndex = Math.max(0, Math.min(selectedIndex, project.clips.size() - 1));
        }
        previewBox.setAspectRatio(project.aspect);
        saveProject();
        rebuildPlaylist(project.clips.isEmpty() ? 0 : project.startOfClip(selectedIndex));
        timeline.setProject(project);
        timeline.setSelectedIndex(selectedIndex);
        refreshPanel();
        refreshHistoryButtons();
        updateChrome();
        rebuildTextOverlays();
    }

    private void refreshHistoryButtons() {
        if (btnUndo == null || btnRedo == null) {
            return;
        }
        btnUndo.setAlpha(undoStack.isEmpty() ? 0.35f : 1f);
        btnRedo.setAlpha(redoStack.isEmpty() ? 0.35f : 1f);
    }

    private void updateChrome() {
        if (project == null) {
            return;
        }
        projectTitle.setText(project.name);
        boolean hasClips = !project.clips.isEmpty();
        boolean hasSelection = selectedIndex >= 0 && selectedIndex < project.clips.size();

        timeline.setProject(project);
        timeline.setSelectedIndex(selectedIndex);
        metaText.setText(project.aspectLabel() + "  ·  "
                + project.clips.size() + " " + clipsWord(project.clips.size())
                + (project.musicUri == null ? "" : "  ·  музыка"));
        emptyHint.setVisibility(hasClips ? View.GONE : View.VISIBLE);
        btnExport.setAlpha(hasClips ? 1f : 0.4f);
        btnExport.setEnabled(hasClips);
        centerPlay.setVisibility(hasClips ? centerPlay.getVisibility() : View.GONE);
        if (!hasSelection && hasClips) {
            metaText.setText(project.aspectLabel() + "  ·  выберите клип  ·  "
                    + project.clips.size() + " " + clipsWord(project.clips.size()));
        }
        refreshHistoryButtons();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_ADD_VIDEO) {
            handlePickedVideos(data);
        } else if (requestCode == REQ_PICK_MEDIA || requestCode == REQ_PICK_FILES) {
            handlePickedMedia(data);
        } else if (requestCode == REQ_MUSIC) {
            handlePickedMusic(data);
        } else if (requestCode == REQ_AUDIO_VIDEO) {
            Uri uri = data.getData();
            if (uri != null) {
                extractAudio(uri, readName(uri));
            }
        }
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
        if (musicPlayer != null) {
            musicPlayer.release();
            musicPlayer = null;
        }
        executor.shutdownNow();
        photoCache.clear();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (exporting) {
            toast("Дождитесь окончания экспорта");
            return;
        }
        if (activePanel != null) {
            closePanel();
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static String clipsWord(int count) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return "клипов";
        }
        if (mod10 == 1) {
            return "клип";
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return "клипа";
        }
        return "клипов";
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.1f с", Math.max(0, ms) / 1000f);
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
