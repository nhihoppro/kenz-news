package com.rightnews.live;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

// v3.10: the loading screen is a dedicated page, not an
// overlay on the main screen (the overlay could never look
// clean: headerBar carries elevation and bottomBar is
// brought to front, so both floated over it). Splash is the
// launcher activity: full-screen, logo + progress only. It
// runs the first news fetch itself, saves the batch to the
// cache, then opens MainActivity — which shows the cached
// news instantly and refreshes in the background as usual.
public class SplashActivity extends Activity {
    private android.os.Handler main = new android.os.Handler();
    private boolean done = false;
    private int fetchGen = 0;
    private android.widget.ProgressBar bar;
    private TextView txtStatus;
    private final Runnable creepRunnable = new Runnable() {
        public void run() {
            if (done || bar == null) return;
            int p = bar.getProgress();
            if (p < 85) { bar.setProgress(p + 2); main.postDelayed(this, 600); }
        }
    };
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        Theme.preferHighRefreshRate(this);
        // Full-screen page: no status bar; the navigation bar
        // follows the theme.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Theme.applyStatusBar(this);
        findViewById(R.id.splashRoot).setBackground(Theme.bgDrawable(this));
        ((TextView) findViewById(R.id.txtSplashTitle)).setTextColor(Theme.headerText(this));
        ((TextView) findViewById(R.id.txtSplashSub)).setTextColor(Theme.subText(this));
        txtStatus = findViewById(R.id.txtSplashStatus);
        if (txtStatus != null) txtStatus.setTextColor(Theme.bodyText(this));
        TextView err = findViewById(R.id.txtSplashError);
        if (err != null) err.setTextColor(Theme.bodyText(this));
        bar = findViewById(R.id.splashProgress);
        if (bar != null) {
            bar.setProgress(10);
        }
        View logo = findViewById(R.id.imgSplashLogo);
        if (logo != null) logo.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse));
        View retry = findViewById(R.id.btnSplashRetry);
        if (retry != null) {
            retry.setBackground(Theme.pillDrawable(this));
            ((TextView) retry).setTextColor(Theme.pillText(this));
            retry.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { runFetch(); }
            });
        }
        View enter = findViewById(R.id.btnSplashEnter);
        if (enter != null) {
            enter.setBackground(Theme.pillDrawable(this));
            ((TextView) enter).setTextColor(Theme.pillText(this));
            enter.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { goMain(); }
            });
        }
        // v3.12: fast entry — a cache means MainActivity shows
        // news at once and refreshes behind it, so the splash
        // only waits for a fetch on a truly empty first launch.
        if (!NewsStore.loadCache(this).isEmpty()) { goMain(); return; }
        runFetch();
    }
    private void runFetch() {
        done = false;
        final int g = ++fetchGen;
        View err = findViewById(R.id.txtSplashError); if (err != null) err.setVisibility(View.GONE);
        View btns = findViewById(R.id.splashErrorBtns); if (btns != null) btns.setVisibility(View.GONE);
        if (bar != null) { bar.setVisibility(View.VISIBLE); bar.setProgress(10); }
        if (txtStatus != null) { txtStatus.setVisibility(View.VISIBLE); txtStatus.setText("Đang tải tin mới…"); }
        main.removeCallbacks(creepRunnable);
        main.postDelayed(creepRunnable, 600);
        // The fetch itself is bounded by RssFetcher's own
        // 35s thread joins; this watchdog covers anything
        // unexpected so the splash can never trap the user.
        main.postDelayed(new Runnable() {
            public void run() { if (fetchGen == g) onFetchDone(null); }
        }, 40000);
        new Thread(new Runnable() {
            public void run() {
                RssFetcher.Result r = null;
                try {
                    final List<Source> srcs = Sources.load(SplashActivity.this);
                    main.post(new Runnable() { public void run() { if (!done && bar != null) bar.setProgress(30); } });
                    r = RssFetcher.fetch(srcs);
                } catch (Exception ignored) {}
                final RssFetcher.Result fr = r;
                main.post(new Runnable() { public void run() { if (fetchGen == g) onFetchDone(fr); } });
            }
        }).start();
    }
    private void onFetchDone(RssFetcher.Result r) {
        if (done) return;
        if (r != null && !r.items.isEmpty()) {
            NewsStore.saveCache(this, r.items);
            // v3.12: the home-screen widget reads the same cache.
            NewsWidgetProvider.updateAll(this);
            Set<String> seen = NewsStore.seen(this);
            Set<String> allKeys = new HashSet<>(seen);
            for (NewsItem it : r.items) allKeys.add(it.key());
            NewsStore.saveSeen(this, allKeys);
            goMain();
            return;
        }
        // Empty fetch: with a cache, MainActivity shows it
        // at once; without one, offer Retry / Enter here.
        if (!NewsStore.loadCache(this).isEmpty()) { goMain(); return; }
        done = true;
        main.removeCallbacks(creepRunnable);
        View logo = findViewById(R.id.imgSplashLogo);
        if (logo != null) logo.clearAnimation();
        if (bar != null) bar.setVisibility(View.GONE);
        if (txtStatus != null) txtStatus.setVisibility(View.GONE);
        View err = findViewById(R.id.txtSplashError); if (err != null) err.setVisibility(View.VISIBLE);
        View btns = findViewById(R.id.splashErrorBtns); if (btns != null) btns.setVisibility(View.VISIBLE);
    }
    private void goMain() {
        if (done) return;
        done = true;
        main.removeCallbacks(creepRunnable);
        if (bar != null) bar.setProgress(100);
        View logo = findViewById(R.id.imgSplashLogo);
        if (logo != null) logo.clearAnimation();
        // Let the full bar register for a beat before the handoff.
        main.postDelayed(new Runnable() {
            public void run() {
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        }, 300);
    }
}
