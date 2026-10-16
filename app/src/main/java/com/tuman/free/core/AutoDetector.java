package com.tuman.free.core;

import android.content.Context;

import com.tuman.free.vpn.NetGuard;
import com.tuman.free.vpn.TpwsRunner;

import java.util.List;

/**
 * Walks the built-in strategies, spins up a throw-away tpws for each of them and
 * measures which one actually gets traffic through. Runs entirely off the UI
 * thread and can be cancelled at any point.
 */
public final class AutoDetector {

    public interface Callback {
        void onProgress(int index, int total, Strategy strategy);

        void onResult(String bestId, String bestName, int bestScore, String report);

        void onCancelled();
    }

    private static final int PORT_BASE = 21001;
    private static final int PORT_SPAN = 30;

    private final Context ctx;
    private volatile boolean cancelled;
    private Thread thread;

    public AutoDetector(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    public void start(final Callback cb) {
        cancel();
        cancelled = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runDetection(cb);
            }
        }, "auto-detect");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public void cancel() {
        cancelled = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** One-off check of a single strategy through a temporary tpws instance. */
    public static Probe.Result checkOnce(Context ctx, String strategyId) {
        Strategy s = Strategies.resolve(ctx, strategyId);
        TpwsRunner r = new TpwsRunner();
        try {
            for (int i = 0; i < PORT_SPAN; i++) {
                if (r.start(ctx, NetGuard.NOOP, PORT_BASE + i, s.args, false)) {
                    return Probe.run(r.port());
                }
            }
            return null;
        } finally {
            r.stop();
        }
    }

    private void runDetection(Callback cb) {
        List<Strategy> list = Strategies.detectable();
        StringBuilder report = new StringBuilder();
        String bestId = null;
        String bestName = null;
        int bestScore = -1;
        long bestMs = Long.MAX_VALUE;

        Engine.log("\u2192 \u0410\u0432\u0442\u043e\u043f\u043e\u0434\u0431\u043e\u0440: " + list.size()
                + " \u0441\u0442\u0440\u0430\u0442\u0435\u0433\u0438\u0439");

        for (int i = 0; i < list.size(); i++) {
            if (cancelled) break;
            final Strategy s = list.get(i);
            final int idx = i;
            Ui.post(new Runnable() {
                @Override
                public void run() {
                    cb.onProgress(idx, list.size(), s);
                }
            });

            Strategy resolved = Strategies.resolve(ctx, s.id);
            TpwsRunner r = new TpwsRunner();
            Probe.Result res = null;
            try {
                for (int k = 0; k < PORT_SPAN; k++) {
                    if (cancelled) break;
                    if (r.start(ctx, NetGuard.NOOP, PORT_BASE + k, resolved.args, false)) {
                        res = Probe.run(r.port());
                        break;
                    }
                }
            } finally {
                r.stop();
            }

            int score = res == null ? 0 : res.score;
            long ms = res == null ? Long.MAX_VALUE : res.totalMs;
            report.append(score).append('/').append(Probe.maxScore())
                    .append("  ").append(s.name)
                    .append(res == null ? "  (\u043d\u0435 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u043b\u043e\u0441\u044c)" : "  " + ms + " \u043c\u0441")
                    .append('\n');
            Engine.log("  " + s.name + ": " + score + "/" + Probe.maxScore()
                    + (res == null ? "" : " \u0437\u0430 " + res.totalMs + " \u043c\u0441"));

            if (score > bestScore || (score == bestScore && ms < bestMs)) {
                bestScore = score;
                bestMs = ms;
                bestId = s.id;
                bestName = s.name;
            }
        }

        final String fId = bestId;
        final String fName = bestName;
        final int fScore = bestScore;
        final String fReport = report.toString();
        final boolean wasCancelled = cancelled;
        Ui.post(new Runnable() {
            @Override
            public void run() {
                if (wasCancelled) cb.onCancelled();
                else cb.onResult(fId, fName, fScore, fReport);
            }
        });
    }
}
