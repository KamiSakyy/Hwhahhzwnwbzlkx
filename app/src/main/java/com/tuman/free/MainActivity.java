package com.tuman.free;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ViewFlipper;

import com.tuman.free.core.AutoDetector;
import com.tuman.free.core.Engine;
import com.tuman.free.core.Prefs;
import com.tuman.free.core.Probe;
import com.tuman.free.core.Strategies;
import com.tuman.free.core.Strategy;
import com.tuman.free.core.Ui;
import com.tuman.free.ui.LogAdapter;
import com.tuman.free.ui.PowerButton;
import com.tuman.free.ui.UiUtil;
import com.tuman.free.vpn.TunnelVpnService;

import java.util.ArrayList;
import java.util.List;

/**
 * Single activity, four pages in a {@link ViewFlipper}, no navigation library.
 */
public class MainActivity extends Activity implements Engine.Listener {

    private static final int REQ_VPN = 4242;
    private static final int REQ_NOTIF = 4243;

    private static final int C_ACCENT = 0xFF2DE2E6;
    private static final int C_DIM = 0xFF5C6E8C;
    private static final int C_OK = 0xFF35E08C;
    private static final int C_ERR = 0xFFFF5C7A;
    private static final int C_WARN = 0xFFFFB454;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> strategyCards = new ArrayList<>();
    private final List<String> strategyIds = new ArrayList<>();
    private final List<View> modeCards = new ArrayList<>();
    private final List<String> modeIds = new ArrayList<>();

    private Prefs prefs;
    private ViewFlipper flipper;
    private PowerButton power;
    private TextView statusTitle;
    private TextView statusSub;
    private TextView chipStrategy;
    private TextView statUptime;
    private TextView statSessions;
    private TextView statTraffic;
    private TextView detectInfo;
    private TextView appsSummary;
    private TextView pillText;
    private View pillDot;
    private TextView btnDetect;
    private TextView btnTest;
    private EditText customArgs;
    private EditText dnsInput;
    private ListView logList;
    private LogAdapter logAdapter;

    private View[] navItems;
    private ImageView[] navIcons;
    private TextView[] navLabels;

    private Switch swUdp;
    private Switch swIpv6;
    private Switch swWatchdog;
    private Switch swVerbose;
    private Switch swAutostart;

    private AutoDetector detector;
    private boolean resumed;
    private boolean syncing;
    private int currentTab;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            updateStats();
            handler.postDelayed(this, 1000L);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        flipper = findViewById(R.id.flipper);
        power = findViewById(R.id.power);
        statusTitle = findViewById(R.id.status_title);
        statusSub = findViewById(R.id.status_sub);
        chipStrategy = findViewById(R.id.chip_strategy);
        statUptime = findViewById(R.id.stat_uptime);
        statSessions = findViewById(R.id.stat_sessions);
        statTraffic = findViewById(R.id.stat_traffic);
        detectInfo = findViewById(R.id.detect_info);
        appsSummary = findViewById(R.id.apps_summary);
        pillText = findViewById(R.id.pill_text);
        pillDot = findViewById(R.id.pill_dot);
        btnDetect = findViewById(R.id.btn_detect);
        btnTest = findViewById(R.id.btn_test);
        customArgs = findViewById(R.id.custom_args);
        dnsInput = findViewById(R.id.dns_input);
        logList = findViewById(R.id.log_list);

        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPower();
            }
        });
        btnDetect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAutoDetect();
            }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCheck();
            }
        });
        findViewById(R.id.btn_dns_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyDns();
            }
        });
        findViewById(R.id.btn_save_custom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCustomArgs();
            }
        });
        findViewById(R.id.btn_clear_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine.clearLog();
                if (logAdapter != null) logAdapter.clear();
            }
        });
        findViewById(R.id.btn_pick_apps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppsActivity.class));
            }
        });

        setupNav();
        setupModes();
        setupSwitches();
        buildStrategies();

        customArgs.setText(prefs.customArgs());
        dnsInput.setText(prefs.dnsRaw());

        logAdapter = new LogAdapter(this);
        logList.setAdapter(logAdapter);
        logList.setDivider(null);

        currentTab = prefs.lastTab();
        if (currentTab < 0 || currentTab > 3) currentTab = 0;
        selectTab(currentTab);

        askForNotificationPermission();
        refresh();
        updateStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Engine.addListener(this);
        handler.post(ticker);
        logAdapter.addAll(Engine.logSnapshot());
        scrollLog(false);
        refresh();
        updateStats();
        updateAppsSummary();
    }

    @Override
    protected void onPause() {
        resumed = false;
        Engine.removeListener(this);
        handler.removeCallbacks(ticker);
        prefs.lastTab(currentTab);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (detector != null) detector.cancel();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startTunnel();
            else Engine.log("\u2717 \u0420\u0430\u0437\u0440\u0435\u0448\u0435\u043d\u0438\u0435 \u043d\u0430 VPN \u043d\u0435 \u0432\u044b\u0434\u0430\u043d\u043e");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    // -------------------------------------------------------------------- engine

    @Override
    public void onEngineChanged() {
        if (resumed) {
            refresh();
            updateStats();
        }
    }

    @Override
    public void onLogLine(String line) {
        if (!resumed || logAdapter == null) return;
        boolean follow = logList.getLastVisiblePosition() >= logAdapter.getCount() - 2;
        logAdapter.add(line);
        scrollLog(follow);
    }

    private void scrollLog(boolean force) {
        if (logAdapter == null || logAdapter.getCount() == 0) return;
        if (force || currentTab == 3) logList.setSelection(logAdapter.getCount() - 1);
    }

    private void refresh() {
        int st = Engine.getState();

        if (st == Engine.STARTING || st == Engine.STOPPING) power.setBusy();
        else if (st == Engine.RUNNING) power.setActive();
        else power.setIdle();

        String title;
        String sub;
        int color;
        switch (st) {
            case Engine.STARTING:
                title = getString(R.string.st_starting);
                sub = getString(R.string.st_starting_sub);
                color = C_WARN;
                break;
            case Engine.RUNNING:
                title = getString(R.string.st_running);
                sub = getString(R.string.st_running_sub, Engine.getStrategyName());
                color = C_OK;
                break;
            case Engine.STOPPING:
                title = getString(R.string.st_stopping);
                sub = getString(R.string.st_stopping_sub);
                color = C_WARN;
                break;
            case Engine.ERROR:
                title = getString(R.string.st_error);
                sub = TextUtils.isEmpty(Engine.getDetail())
                        ? getString(R.string.st_error_sub) : Engine.getDetail();
                color = C_ERR;
                break;
            default:
                title = getString(R.string.st_stopped);
                sub = getString(R.string.st_stopped_sub);
                color = C_DIM;
                break;
        }

        statusTitle.setText(title);
        statusTitle.setTextColor(color);
        statusSub.setText(sub);

        pillText.setText(st == Engine.RUNNING ? getString(R.string.pill_on) : getString(R.string.pill_off));
        pillText.setTextColor(st == Engine.RUNNING ? C_ACCENT : C_DIM);
        tintDot(st == Engine.RUNNING ? C_ACCENT : C_DIM);

        String name = Strategies.byId(prefs.strategy()).name;
        chipStrategy.setText(getString(R.string.chip_strategy, name));
    }

    private void tintDot(int color) {
        if (pillDot == null) return;
        android.graphics.drawable.Drawable d = pillDot.getBackground();
        if (d != null) {
            d = d.mutate();
            pillDot.setBackground(d);
            if (Build.VERSION.SDK_INT >= 21) d.setTint(color);
        }
    }

    private void updateStats() {
        statUptime.setText(UiUtil.uptime(Engine.getStartedAt()));
        statSessions.setText(String.valueOf(Engine.activeSessions.get()));
        long total = Engine.bytesUp.get() + Engine.bytesDown.get();
        statTraffic.setText(UiUtil.traffic(total));
    }

    private void askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------- actions

    private void onPower() {
        int st = Engine.getState();
        if (st == Engine.RUNNING || st == Engine.STARTING) {
            stopTunnel();
            return;
        }
        Intent prepare = null;
        try {
            prepare = VpnService.prepare(this);
        } catch (Throwable t) {
            Engine.log("! prepare: " + t.getMessage());
        }
        if (prepare != null) {
            try {
                startActivityForResult(prepare, REQ_VPN);
                return;
            } catch (Throwable ignored) {
            }
        }
        startTunnel();
    }

    private void startTunnel() {
        try {
            Intent i = new Intent(this, TunnelVpnService.class);
            i.setAction(TunnelVpnService.ACTION_START);
            i.putExtra(TunnelVpnService.EXTRA_STRATEGY, prefs.strategy());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            Engine.fail("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c \u0441\u0435\u0440\u0432\u0438\u0441: " + t.getMessage());
        }
    }

    private void stopTunnel() {
        try {
            Intent i = new Intent(this, TunnelVpnService.class);
            i.setAction(TunnelVpnService.ACTION_STOP);
            startService(i);
        } catch (Throwable t) {
            Engine.log("! stop: " + t.getMessage());
        }
    }

    private void restartTunnel() {
        if (Engine.isRunning() || Engine.getState() == Engine.STARTING) startTunnel();
    }

    private void applyDns() {
        String v = dnsInput.getText().toString().trim();
        if (v.length() == 0) {
            dnsInput.setText(prefs.dnsRaw());
            return;
        }
        prefs.dnsRaw(v);
        Engine.log("\u2192 DNS: " + prefs.dnsRaw());
        restartTunnel();
    }

    private void saveCustomArgs() {
        String v = customArgs.getText().toString().trim();
        prefs.customArgs(v);
        prefs.strategy(Strategies.CUSTOM_ID);
        buildStrategies();
        Engine.log("\u2192 \u0421\u0432\u043e\u0438 \u0430\u0440\u0433\u0443\u043c\u0435\u043d\u0442\u044b \u0441\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u044b");
        if (v.length() == 0) {
            detectInfo.setVisibility(View.VISIBLE);
            detectInfo.setText(R.string.custom_empty_warn);
        }
        refresh();
        restartTunnel();
    }

    private void onAutoDetect() {
        if (detector != null && detector.isRunning()) {
            detector.cancel();
            detectInfo.setVisibility(View.VISIBLE);
            detectInfo.setText(R.string.detect_cancelled);
            btnDetect.setText(R.string.btn_autodetect);
            return;
        }
        detector = new AutoDetector(this);
        detectInfo.setVisibility(View.VISIBLE);
        detectInfo.setText(R.string.detect_start);
        btnDetect.setText(R.string.btn_cancel);
        detector.start(new AutoDetector.Callback() {
            @Override
            public void onProgress(int index, int total, Strategy strategy) {
                if (!resumed) return;
                detectInfo.setText(getString(R.string.detect_progress, index + 1, total, strategy.name));
            }

            @Override
            public void onResult(String bestId, String bestName, int bestScore, String report) {
                btnDetect.setText(R.string.btn_autodetect);
                if (!resumed) return;
                if (bestId == null || bestScore <= 0) {
                    detectInfo.setText(R.string.detect_none);
                    Engine.log("\u2717 \u0410\u0432\u0442\u043e\u043f\u043e\u0434\u0431\u043e\u0440: \u043d\u0438 \u043e\u0434\u043d\u0430 \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u044f \u043d\u0435 \u043f\u0440\u043e\u0448\u043b\u0430");
                    return;
                }
                prefs.strategy(bestId);
                buildStrategies();
                refresh();
                detectInfo.setText(getString(R.string.detect_done, bestName, bestScore, Probe.maxScore()));
                Engine.log("\u2713 \u0410\u0432\u0442\u043e\u043f\u043e\u0434\u0431\u043e\u0440: " + bestName);
                restartTunnel();
            }

            @Override
            public void onCancelled() {
                btnDetect.setText(R.string.btn_autodetect);
                if (resumed) detectInfo.setText(R.string.detect_cancelled);
            }
        });
    }

    private void onCheck() {
        btnTest.setEnabled(false);
        detectInfo.setVisibility(View.VISIBLE);
        detectInfo.setText(R.string.check_running);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Probe.Result r;
                int port = Engine.getSocksPort();
                if (Engine.isRunning() && port > 0) r = Probe.run(port);
                else r = AutoDetector.checkOnce(getApplicationContext(), prefs.strategy());
                final Probe.Result result = r;
                Ui.post(new Runnable() {
                    @Override
                    public void run() {
                        btnTest.setEnabled(true);
                        showProbe(result);
                    }
                });
            }
        }, "probe").start();
    }

    private void showProbe(Probe.Result r) {
        if (r == null) {
            detectInfo.setText(R.string.check_failed);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Probe.Target t : r.targets) {
            sb.append(t.ok ? "\u2713 " : "\u2717 ")
                    .append(UiUtil.host(t.url))
                    .append(t.ok ? "  " + t.ms + " \u043c\u0441" : "  " + shortError(t.error))
                    .append('\n');
        }
        sb.append(getString(R.string.check_score, r.score, Probe.maxScore()));
        detectInfo.setText(sb.toString().trim());
        Engine.log((r.perfect() ? "\u2713 " : "! ") + "\u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430: "
                + r.score + "/" + Probe.maxScore());
    }

    private static String shortError(String e) {
        if (e == null) return "";
        String s = e.length() > 42 ? e.substring(0, 42) + "\u2026" : e;
        return s;
    }

    // ---------------------------------------------------------------------- nav

    private void setupNav() {
        int[] ids = {R.id.nav_home, R.id.nav_strategies, R.id.nav_apps, R.id.nav_logs};
        navItems = new View[ids.length];
        navIcons = new ImageView[ids.length];
        navLabels = new TextView[ids.length];
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            View item = findViewById(ids[i]);
            navItems[i] = item;
            navIcons[i] = (ImageView) ((LinearLayout) item).getChildAt(0);
            navLabels[i] = (TextView) ((LinearLayout) item).getChildAt(1);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
        }
    }

    private void selectTab(int index) {
        currentTab = index;
        flipper.setDisplayedChild(index);
        for (int i = 0; i < navItems.length; i++) {
            boolean sel = i == index;
            navItems[i].setBackgroundResource(sel ? R.drawable.bg_nav_on : R.drawable.bg_nav_off);
            navIcons[i].setColorFilter(sel ? C_ACCENT : C_DIM);
            navLabels[i].setTextColor(sel ? C_ACCENT : C_DIM);
        }
        if (index == 3 && logAdapter != null) {
            logAdapter.addAll(Engine.logSnapshot());
            scrollLog(true);
        }
        if (index == 2) updateAppsSummary();
    }

    // ----------------------------------------------------------------- strategies

    private void buildStrategies() {
        LinearLayout host = findViewById(R.id.strategy_list);
        if (host == null) return;
        host.removeAllViews();
        strategyCards.clear();
        strategyIds.clear();
        for (Strategy s : Strategies.all()) {
            View card = getLayoutInflater().inflate(R.layout.item_strategy, host, false);
            ((TextView) card.findViewById(R.id.st_name)).setText(s.name);
            ((TextView) card.findViewById(R.id.st_desc)).setText(s.desc);
            ((TextView) card.findViewById(R.id.st_args)).setText(s.argsString());
            final String id = s.id;
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickStrategy(id);
                }
            });
            host.addView(card);
            strategyCards.add(card);
            strategyIds.add(id);
        }
        highlightStrategy();
    }

    private void pickStrategy(String id) {
        prefs.strategy(id);
        highlightStrategy();
        refresh();
        Engine.log("\u2192 \u0421\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u044f: " + Strategies.byId(id).name);
        restartTunnel();
    }

    private void highlightStrategy() {
        String sel = prefs.strategy();
        for (int i = 0; i < strategyCards.size(); i++) {
            boolean on = strategyIds.get(i).equals(sel);
            View card = strategyCards.get(i);
            card.setBackgroundResource(on ? R.drawable.bg_card_active : R.drawable.bg_card);
            View mark = card.findViewById(R.id.st_mark);
            if (mark != null) mark.setBackgroundResource(on ? R.drawable.radio_on : R.drawable.radio_off);
        }
    }

    // ------------------------------------------------------------- split tunnel

    private void setupModes() {
        int[] ids = {R.id.mode_all, R.id.mode_include, R.id.mode_exclude};
        String[] modes = {Prefs.MODE_ALL, Prefs.MODE_INCLUDE, Prefs.MODE_EXCLUDE};
        modeCards.clear();
        modeIds.clear();
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            View card = findViewById(ids[i]);
            if (card == null) continue;
            modeCards.add(card);
            modeIds.add(modes[index]);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.tunnelMode(modeIds.get(index));
                    highlightModes();
                    updateAppsSummary();
                    restartTunnel();
                }
            });
        }
        highlightModes();
        updateAppsSummary();
    }

    private void highlightModes() {
        String sel = prefs.tunnelMode();
        for (int i = 0; i < modeCards.size(); i++) {
            boolean on = modeIds.get(i).equals(sel);
            modeCards.get(i).setBackgroundResource(on ? R.drawable.bg_card_active : R.drawable.bg_card);
            View mark = modeCards.get(i).findViewById(R.id.mode_mark);
            if (mark != null) mark.setBackgroundResource(on ? R.drawable.radio_on : R.drawable.radio_off);
        }
    }

    private void updateAppsSummary() {
        if (appsSummary == null) return;
        String mode = prefs.tunnelMode();
        int n = prefs.tunnelApps().size();
        if (Prefs.MODE_ALL.equals(mode)) appsSummary.setText(R.string.apps_mode_all);
        else if (Prefs.MODE_INCLUDE.equals(mode)) {
            appsSummary.setText(n == 0 ? getString(R.string.apps_include_empty)
                    : getResources().getQuantityString(R.plurals.apps_selected, n, n));
        } else {
            appsSummary.setText(n == 0 ? getString(R.string.apps_exclude_empty)
                    : getResources().getQuantityString(R.plurals.apps_excluded, n, n));
        }
    }

    // ------------------------------------------------------------------ switches

    private void setupSwitches() {
        swUdp = bindSwitch(R.id.row_udp, R.string.sw_udp, R.string.sw_udp_sub, prefs.blockUdp(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        if (syncing) return;
                        prefs.blockUdp(checked);
                        Engine.log("\u2192 QUIC/UDP: " + (checked ? "\u0431\u043b\u043e\u043a" : "\u043f\u0440\u043e\u043f\u0443\u0441\u043a"));
                        restartTunnel();
                    }
                });
        swIpv6 = bindSwitch(R.id.row_ipv6, R.string.sw_ipv6, R.string.sw_ipv6_sub, prefs.blockIpv6(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        if (syncing) return;
                        prefs.blockIpv6(checked);
                        restartTunnel();
                    }
                });
        swWatchdog = bindSwitch(R.id.row_watchdog, R.string.sw_watchdog, R.string.sw_watchdog_sub,
                prefs.watchdog(), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        if (syncing) return;
                        prefs.watchdog(checked);
                    }
                });
        swVerbose = bindSwitch(R.id.row_verbose, R.string.sw_verbose, R.string.sw_verbose_sub,
                prefs.verboseLog(), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        if (syncing) return;
                        prefs.verboseLog(checked);
                        restartTunnel();
                    }
                });
        swAutostart = bindSwitch(R.id.row_autostart, R.string.sw_autostart, R.string.sw_autostart_sub,
                prefs.autostart(), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        if (syncing) return;
                        prefs.autostart(checked);
                    }
                });
    }

    private Switch bindSwitch(int rowId, int titleRes, int subRes, boolean value,
                              CompoundButton.OnCheckedChangeListener listener) {
        View row = findViewById(rowId);
        if (row == null) return null;
        ((TextView) row.findViewById(R.id.sw_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.sw_sub)).setText(subRes);
        Switch sw = row.findViewById(R.id.sw_toggle);
        syncing = true;
        sw.setChecked(value);
        syncing = false;
        sw.setOnCheckedChangeListener(listener);
        return sw;
    }
}
