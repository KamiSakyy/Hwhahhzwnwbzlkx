package com.tuman.free.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Append-only monospace log list with severity colouring. */
public class LogAdapter extends BaseAdapter {

    private static final int MAX = 1200;

    private static final int C_INFO = 0xFFB9C6DE;
    private static final int C_OK = 0xFF35E08C;
    private static final int C_WARN = 0xFFFFB454;
    private static final int C_ERR = 0xFFFF5C7A;
    private static final int C_PROC = 0xFF6E86AD;

    private final List<String> lines = new ArrayList<>();
    private final float textSizePx;
    private final Typeface mono;

    public LogAdapter(Context ctx) {
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f,
                ctx.getResources().getDisplayMetrics());
        Typeface m = Typeface.MONOSPACE;
        try {
            m = Typeface.create("monospace", Typeface.NORMAL);
        } catch (Throwable ignored) {
        }
        mono = m == null ? Typeface.MONOSPACE : m;
    }

    public void addAll(List<String> initial) {
        lines.clear();
        if (initial != null) lines.addAll(initial);
        notifyDataSetChanged();
    }

    public void add(String line) {
        lines.add(line);
        while (lines.size() > MAX) lines.remove(0);
        notifyDataSetChanged();
    }

    public void clear() {
        lines.clear();
        notifyDataSetChanged();
    }

    public int size() {
        return lines.size();
    }

    @Override
    public int getCount() {
        return lines.size();
    }

    @Override
    public String getItem(int position) {
        return lines.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tv;
        if (convertView instanceof TextView) {
            tv = (TextView) convertView;
        } else {
            tv = new TextView(parent.getContext());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
            tv.setTypeface(mono);
            tv.setSingleLine(false);
            int pad = UiUtil.dp(parent.getContext(), 1f);
            tv.setPadding(0, pad * 3, 0, pad * 3);
        }
        String line = lines.get(position);
        tv.setText(line);
        tv.setTextColor(colorFor(line));
        return tv;
    }

    private static int colorFor(String line) {
        if (line.indexOf("\u2717") >= 0) return C_ERR;    // heavy cross
        if (line.indexOf("\u21ba") >= 0) return C_WARN;   // restart arrow
        if (line.contains("! ")) return C_WARN;
        if (line.indexOf("\u2713") >= 0) return C_OK;     // check mark
        if (line.contains("[tpws]")) return C_PROC;
        return C_INFO;
    }
}
