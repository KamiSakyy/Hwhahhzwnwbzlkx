package com.telegramthemeeditor.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private final Map<String, Integer> themeColors = new HashMap<>();
    private View root, actionBar, bubbleIn, bubbleOut, unreadDivider, inputBar, inputField, sendButton;
    private TextView actionBarTitle, actionBarSubtitle, bubbleInName, bubbleInText, bubbleInTime,
            bubbleOutText, bubbleOutTime, unreadDividerText, inputFieldText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        actionBar = findViewById(R.id.actionBar);
        bubbleIn = findViewById(R.id.bubbleIn);
        bubbleOut = findViewById(R.id.bubbleOut);
        unreadDivider = findViewById(R.id.unreadDivider);
        inputBar = findViewById(R.id.inputBar);
        inputField = findViewById(R.id.inputField);
        sendButton = findViewById(R.id.sendButton);

        actionBarTitle = findViewById(R.id.actionBarTitle);
        actionBarSubtitle = findViewById(R.id.actionBarSubtitle);
        bubbleInName = findViewById(R.id.bubbleInName);
        bubbleInText = findViewById(R.id.bubbleInText);
        bubbleInTime = findViewById(R.id.bubbleInTime);
        bubbleOutText = findViewById(R.id.bubbleOutText);
        bubbleOutTime = findViewById(R.id.bubbleOutTime);
        unreadDividerText = (TextView) unreadDivider;
        inputFieldText = (TextView) inputField;

        // Clickable mappings: view -> (key, isText)
        makeEditable(root, "chat_wallpaper", false);
        makeEditable(actionBar, "actionBarDefault", false);
        makeTextEditable(actionBarTitle, "actionBarDefaultTitle");
        makeTextEditable(actionBarSubtitle, "actionBarDefaultSubtitle");
        makeEditable(bubbleIn, "chat_inBubble", false);
        makeTextEditable(bubbleInName, "chat_inBubbleNameText"); // may not exist but ok
        makeTextEditable(bubbleInText, "chat_messageTextIn");
        makeTextEditable(bubbleInTime, "chat_inDateText");
        makeEditable(bubbleOut, "chat_outBubble", false);
        makeTextEditable(bubbleOutText, "chat_messageTextOut");
        makeTextEditable(bubbleOutTime, "chat_outDateText");
        makeEditable(unreadDivider, "chat_unreadMessagesStartBackground", false);
        makeTextEditable(unreadDividerText, "chat_unreadMessagesStartText");
        makeEditable(inputBar, "chat_messagePanelBackground", false);
        makeEditable(inputField, "chat_messagePanelInputFieldBackground", false);
        makeTextEditable(inputFieldText, "chat_messagePanelHint");
        makeTextEditable(sendButton, "chat_messagePanelSend");

        Button btnWallpaper = findViewById(R.id.btnWallpaper);
        Button btnExport = findViewById(R.id.btnExport);
        Button btnReset = findViewById(R.id.btnReset);

        btnWallpaper.setOnClickListener(v -> pickWallpaper());
        btnExport.setOnClickListener(v -> exportTheme());
        btnReset.setOnClickListener(v -> resetTheme());
    }

    private void makeEditable(View v, String key, boolean isText) {
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(view -> showColorPicker(key, color -> {
            view.setBackgroundColor(color);
            themeColors.put(key, color);
        }));
    }

    private void makeTextEditable(TextView tv, String key) {
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(view -> showColorPicker(key, color -> {
            ((TextView) view).setTextColor(color);
            themeColors.put(key, color);
        }));
    }

    private void showColorPicker(String key, ColorCallback cb) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        TextView title = new TextView(this);
        title.setText(key);
        title.setTextSize(16);
        layout.addView(title);

        View preview = new View(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120);
        pp.setMargins(0, 16, 0, 16);
        preview.setLayoutParams(pp);
        layout.addView(preview);

        SeekBar sbA = makeSlider(layout, "Alpha", 255);
        SeekBar sbR = makeSlider(layout, "Red", 255);
        SeekBar sbG = makeSlider(layout, "Green", 255);
        SeekBar sbB = makeSlider(layout, "Blue", 255);

        int initial = themeColors.containsKey(key) ? themeColors.get(key) : Color.WHITE;
        sbA.setProgress(Color.alpha(initial));
        sbR.setProgress(Color.red(initial));
        sbG.setProgress(Color.green(initial));
        sbB.setProgress(Color.blue(initial));

        Runnable update = () -> {
            int c = Color.argb(sbA.getProgress(), sbR.getProgress(), sbG.getProgress(), sbB.getProgress());
            preview.setBackgroundColor(c);
        };
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { update.run(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        sbA.setOnSeekBarChangeListener(l);
        sbR.setOnSeekBarChangeListener(l);
        sbG.setOnSeekBarChangeListener(l);
        sbB.setOnSeekBarChangeListener(l);
        update.run();

        new AlertDialog.Builder(this)
                .setTitle("Edit color")
                .setView(layout)
                .setPositiveButton("Apply", (d, w) -> {
                    int c = Color.argb(sbA.getProgress(), sbR.getProgress(), sbG.getProgress(), sbB.getProgress());
                    cb.onColor(c);
                    Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private SeekBar makeSlider(LinearLayout parent, String label, int max) {
        TextView t = new TextView(this);
        t.setText(label);
        parent.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        parent.addView(sb);
        return sb;
    }

    interface ColorCallback { void onColor(int color); }

    private void pickWallpaper() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                root.setBackground(null);
                root.setBackgroundColor(Color.TRANSPARENT);
                // set wallpaper as background drawable via content resolver
                android.graphics.drawable.Drawable d = android.graphics.drawable.Drawable.createFromStream(
                        getContentResolver().openInputStream(uri), null);
                root.setBackground(d);
                themeColors.put("chat_wallpaper", 0);
                Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void exportTheme() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : themeColors.entrySet()) {
            // Telegram .attheme uses signed decimal
            sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
        }
        if (sb.length() == 0) {
            Toast.makeText(this, "No changes", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir = getCacheDir();
            File file = new File(dir, "theme.attheme");
            FileWriter fw = new FileWriter(file);
            fw.write(sb.toString());
            fw.close();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/octet-stream");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share theme"));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetTheme() {
        themeColors.clear();
        recreate();
    }
}
