package com.tuman.free;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.tuman.free.core.Engine;
import com.tuman.free.core.Prefs;
import com.tuman.free.core.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Split-tunnel picker: choose which apps go through the tunnel (or which ones
 * are excluded from it). Uses plain framework widgets, no RecyclerView.
 */
public class AppsActivity extends Activity {

    private static final class AppEntry {
        final String pkg;
        final String name;
        final boolean system;

        AppEntry(String pkg, String name, boolean system) {
            this.pkg = pkg;
            this.name = name;
            this.system = system;
        }
    }

    private Prefs prefs;
    private ListView list;
    private AppAdapter adapter;
    private TextView counter;
    private EditText search;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(120);
    private volatile boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        prefs = new Prefs(this);
        selected.addAll(prefs.tunnelApps());

        list = findViewById(R.id.app_list);
        counter = findViewById(R.id.apps_count);
        search = findViewById(R.id.app_search);

        adapter = new AppAdapter();
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                applyFilter(e == null ? "" : e.toString());
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_done).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btn_select_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (AppEntry e : shown) selected.add(e.pkg);
                adapter.notifyDataSetChanged();
                updateCounter();
            }
        });
        findViewById(R.id.btn_select_none).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (AppEntry e : shown) selected.remove(e.pkg);
                adapter.notifyDataSetChanged();
                updateCounter();
            }
        });
        findViewById(R.id.btn_invert).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (AppEntry e : shown) {
                    if (selected.contains(e.pkg)) selected.remove(e.pkg);
                    else selected.add(e.pkg);
                }
                adapter.notifyDataSetChanged();
                updateCounter();
            }
        });

        updateCounter();
        loadApps();
    }

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> out = new ArrayList<>();
                try {
                    List<ApplicationInfo> infos = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : infos) {
                        if (ai.packageName.equals(self)) continue;
                        if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                        CharSequence label = ai.loadLabel(pm);
                        String name = label == null ? ai.packageName : label.toString();
                        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        out.add(new AppEntry(ai.packageName, name, system));
                    }
                } catch (Throwable t2) {
                    Engine.log("! \u0441\u043f\u0438\u0441\u043e\u043a \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439: " + t2.getMessage());
                }
                Collections.sort(out, new Comparator<AppEntry>() {
                    @Override
                    public int compare(AppEntry a, AppEntry b) {
                        int c = a.name.compareToIgnoreCase(b.name);
                        return c != 0 ? c : a.pkg.compareTo(b.pkg);
                    }
                });
                Ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        all.clear();
                        all.addAll(out);
                        applyFilter(search.getText() == null ? "" : search.getText().toString());
                    }
                });
            }
        }, "app-loader");
        t.setDaemon(true);
        t.start();
    }

    private void applyFilter(String query) {
        shown.clear();
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.length() == 0) {
            shown.addAll(all);
        } else {
            for (AppEntry e : all) {
                if (e.name.toLowerCase().contains(q) || e.pkg.toLowerCase().contains(q)) shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
        updateCounter();
    }

    private void updateCounter() {
        if (counter == null) return;
        if (loading) {
            counter.setText(R.string.apps_loading);
            return;
        }
        counter.setText(getResources().getQuantityString(R.plurals.apps_selected, selected.size(), selected.size())
                + " \u00b7 " + getResources().getQuantityString(R.plurals.apps_total, all.size(), all.size()));
    }

    private void save() {
        prefs.tunnelApps(selected);
        Engine.log("\u2192 \u0421\u043f\u0438\u0441\u043e\u043a \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439: " + selected.size());
        if (Engine.isRunning()) {
            try {
                android.content.Intent i = new android.content.Intent(this, com.tuman.free.vpn.TunnelVpnService.class);
                i.setAction(com.tuman.free.vpn.TunnelVpnService.ACTION_START);
                startService(i);
            } catch (Throwable ignored) {
            }
        }
        finish();
    }

    private Drawable iconFor(AppEntry e, PackageManager pm) {
        Drawable d = icons.get(e.pkg);
        if (d != null) return d;
        try {
            d = pm.getApplicationIcon(e.pkg);
        } catch (Throwable t) {
            d = null;
        }
        if (d != null) icons.put(e.pkg, d);
        return d;
    }

    // -------------------------------------------------------------------- adapter

    private final class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_app, parent, false);

            final AppEntry e = shown.get(position);
            ((TextView) v.findViewById(R.id.app_name)).setText(e.name);
            ((TextView) v.findViewById(R.id.app_pkg)).setText(e.pkg);

            ImageView icon = v.findViewById(R.id.app_icon);
            Drawable d = iconFor(e, getPackageManager());
            if (d != null) icon.setImageDrawable(d);
            else icon.setImageResource(R.drawable.ic_app_placeholder);

            CheckBox cb = v.findViewById(R.id.app_check);
            cb.setChecked(selected.contains(e.pkg));
            cb.setClickable(false);
            cb.setFocusable(false);

            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (selected.contains(e.pkg)) selected.remove(e.pkg);
                    else selected.add(e.pkg);
                    notifyDataSetChanged();
                    updateCounter();
                }
            });
            return v;
        }
    }
}
