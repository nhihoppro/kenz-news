package com.rightnews.live;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.text.SimpleDateFormat;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import java.text.Normalizer;
import java.util.*;
public class MainActivity extends Activity {
    private List<NewsItem> all = new ArrayList<>();
    private List<Source> sources;
    private NewsAdapter newsAdapter, videoAdapter, savedAdapter;
    // Nightly: Saved tab mode — 0 = Đã lưu, 1 = Lịch sử đã đọc.
    private int savedMode = 0;
    private List<NewsItem> videoNews = new ArrayList<>();
    // v1.14: the update status line shows, then hides itself after
    // 5s. The hide runnable is cancelled and re-posted on every
    // update, so a refresh inside the window restarts the timer
    // instead of hiding the new line early.
    private final Runnable hideStatusRunnable = new Runnable() {
        public void run() { if (txtStatus != null) { txtStatus.setVisibility(View.GONE); syncContentTopPadding(); } }
    };
    private void showStatus(String s) {
        if (txtStatus == null) return;
        txtStatus.removeCallbacks(hideStatusRunnable);
        txtStatus.setText(s);
        txtStatus.setVisibility(View.VISIBLE);
        txtStatus.postDelayed(hideStatusRunnable, 5000);
        syncContentTopPadding();
    }
    private void showStatusSticky(String s) {
        if (txtStatus == null) return;
        txtStatus.removeCallbacks(hideStatusRunnable);
        txtStatus.setText(s);
        txtStatus.setVisibility(View.VISIBLE);
        syncContentTopPadding();
    }
    private TextView txtStatus, txtVideoHead, txtLiveHead;
    private TextView txtVideoNote;
    private boolean videoLoading = false;
    private String lastNewsStatus = "", videoStatus = "Video: chưa tải";
    private LinearLayout liveContainer, liveStickerRow;
    private List<LiveChecker.LiveChannel> liveChannels;
    private String expandedLiveId;
    private TextView txtAppTitle;
    private BlurView bottomBlur, topBlur;
    private View newsBox, videoBox, savedBox, lotteryBox, topBar, bottomBar, headerBar;
    private int tab = 0;
    // v3.12: signature of the enabled source set — Sources
    // now live in Settings, so a toggle there must reach
    // this activity on return (see onResume).
    private String sourcesSig = "";
    // v3.3: lottery tab (Xổ số 3 miền, Minh Ngọc). Polls the
    // region page every 10s only while the tab is open, the
    // date is today, and the time is near that region's draw.
    // v3.5: default region is Miền Nam (draws first, 16h15),
    // and the poll force-fetches — a cache-first poll would
    // just re-paint the stale result forever (found while
    // adding the live dot).
    private int lotteryRegion = LotteryFetcher.MN;
    private Calendar lotteryDate;
    private Map<String, LotteryFetcher.DayResult> lotteryCache = new HashMap<>();
    private boolean lotteryLoading = false;
    private String lotteryStatusLine = "";
    // v3.31: live per-number reveal — numbers that arrived
    // in a recent live poll, keyed "dateKey|station|prize|
    // number" with their arrival time, so the card can
    // highlight each number the moment it lands (the
    // highlight fades after 90s). livePollNewCount is how
    // many landed in the latest poll, for the status line.
    private Map<String, Long> liveNewAt = new HashMap<>();
    private int livePollNewCount = 0;
    // v3.32: live "rolling numbers" — while a prize is being
    // drawn, its numbers slot cycles random digits (90ms)
    // until the real result lands on the next poll. Only one
    // prize rolls at a time: the smallest-rank prize still
    // being drawn. rollingBase is the numbers already drawn
    // for that prize ("" when none yet) — the ticker appends
    // the rolling digits after them.
    private final Handler rollHandler = new Handler(Looper.getMainLooper());
    private TextView rollingNumsView = null;
    private CharSequence rollingBase = "";
    private int rollingDigits = 0;
    private final Runnable rollRunnable = new Runnable() {
        public void run() {
            if (rollingNumsView == null) return;
            rollingNumsView.setText(rollingText(rollingBase, rollingDigits));
            rollHandler.postDelayed(this, 90);
        }
    };
    private static CharSequence rollingText(CharSequence base, int digits) {
        SpannableStringBuilder sb = new SpannableStringBuilder(base == null ? "" : base);
        if (sb.length() > 0) sb.append("   ");
        for (int i = 0; i < digits; i++) sb.append((char) ('0' + (int) (Math.random() * 10)));
        return sb;
    }
    private void startRolling() {
        if (rollingNumsView == null) return;
        rollHandler.removeCallbacks(rollRunnable);
        rollHandler.post(rollRunnable);
    }
    private void stopRolling() {
        rollHandler.removeCallbacks(rollRunnable);
        rollingNumsView = null;
        rollingBase = "";
        rollingDigits = 0;
    }
    private void maybeStartRolling() {
        if (tab == 2 && rollingNumsView != null) startRolling();
    }
    // v3.12: the draws currently shown on the lottery tab
    // (the dò vé số check runs against these).
    private List<LotteryFetcher.Draw> currentLotteryDraws = new ArrayList<>();
    // v3.20: station sub-tabs — the selected station's code
    // ("XSBT", "MB", ...) and the region+date it belongs to,
    // so a live-poll re-render keeps the station but a
    // region/date change resets to the first station.
    private String lotteryStationCode = null;
    private String lotteryStationKey = null;
    private final Handler lotteryPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable lotteryPollRunnable = new Runnable() {
        public void run() {
            if (tab == 2 && lotteryDate != null && LotteryFetcher.sameDay(lotteryDate, Calendar.getInstance()))
                refreshLottery(true);
            scheduleLotteryPoll();
        }
    };
    // v3.1: search (header magnifier + row above the chips) and
    // saved-for-later. compactAtCreate/fontAtCreate detect a
    // Settings change on return so the adapters rebuild.
    private String searchQuery = "";
    private boolean compactAtCreate, searchOpen = false;
    private int fontAtCreate = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    // v1.23 pull-to-refresh + refresh guard. One gesture at a time
    // (only one tab is visible), so one listener + one set of
    // fields serves all three scroll views. pullTopY = finger Y
    // when the view first reached the top during this gesture
    // (-1 = not at top yet); pull is measured from topReached, NOT
    // from DOWN, so a long scroll-to-top (500dp drag to reach the
    // top) does not falsely trigger — only overscroll past the top
    // counts (drag since top >= 120dp, a deliberate 2cm pull, not
    // incidental fling overscroll). pullHint = last pull hint
    // shown ("Kéo…" / "Thả…", null = none), so showStatus is only
    // called when the hint changes, not on every MOVE. refreshing
    // + refreshGen + refreshLeft guard the two parallel posts
    // (news, video, all on the main thread, so the
    // decrements cannot interleave); a 90s safety valve clears a
    // stuck flag if a thread ever crashes without posting, and
    // stale posts (gen != current) are ignored so a late post
    // cannot clear a newer refresh's flag early.
    private float pullTopY = -1;
    private float pullThresholdPx = 0;
    private String pullHint = null;
    private boolean refreshing = false;
    private int refreshGen = 0;
    private int refreshLeft = 0;
    // v1.24: pull-to-refresh shows the reload icon in the status
    // strip (user: "hieu ung keo xuong se hien icon tai lai" —
    // the pull effect shows the reload icon). While pulling,
    // the icon turns 0→360deg as the pull goes 0→threshold;
    // while any refresh runs (pull or button tap) it spins.
    // A RotateDrawable cannot take a drawable in code (no
    // setDrawable — verified against android-35) and an XML
    // <rotate> cannot be tinted per theme, so this small wrapper
    // does both: the inner vector is tinted to the status colour
    // at creation (white would vanish on the light-mode white
    // pill), and draw() rotates it around the centre of whatever
    // bounds the TextView gives it (its intrinsic size, so the
    // strip lays it out like any compound drawable).
    private static class SpinDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.drawable.Drawable inner;
        private final int sizePx;
        private float angle;
        SpinDrawable(android.graphics.drawable.Drawable d, int sizePx) { inner = d; this.sizePx = sizePx; }
        void setAngle(float a) { angle = a; invalidateSelf(); }
        float getAngle() { return angle; }
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            c.save();
            c.rotate(angle, b.exactCenterX(), b.exactCenterY());
            inner.setBounds(b);
            inner.draw(c);
            c.restore();
        }
        public void setAlpha(int a) { inner.setAlpha(a); }
        public void setColorFilter(android.graphics.ColorFilter f) { inner.setColorFilter(f); }
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        public int getIntrinsicWidth() { return sizePx; }
        public int getIntrinsicHeight() { return sizePx; }
    }
    private SpinDrawable pullSpin;
    private android.graphics.drawable.Drawable pullSpinInner;
    // +7.2deg every 16ms = one full turn per 800ms (v1.25:
    // 16ms steps, so the spin stays smooth on a 144Hz screen
    // instead of visibly stepping at 20fps). Stops via
    // hidePullIcon (refresh done, 90s valve, cancelled pull).
    private final Runnable spinRunnable = new Runnable() {
        public void run() {
            if (!refreshing || pullSpin == null) return;
            pullSpin.setAngle(pullSpin.getAngle() + 7.2f);
            main.postDelayed(this, 16);
        }
    };
    private void ensurePullSpin() {
        if (txtStatus == null) return;
        if (pullSpin == null) {
            pullSpinInner = getResources().getDrawable(R.drawable.ic_refresh, null);
            pullSpinInner.setTint(Theme.subText(this));
            int px = (int) (20f * getResources().getDisplayMetrics().density);
            pullSpin = new SpinDrawable(pullSpinInner, px);
            txtStatus.setCompoundDrawablePadding((int) (8f * getResources().getDisplayMetrics().density));
        }
    }
    private void showPullIcon(float progress) {
        ensurePullSpin();
        if (txtStatus == null || pullSpin == null) return;
        float p = progress < 0 ? 0 : (progress > 1 ? 1 : progress);
        pullSpin.setAngle(p * 360f);
        txtStatus.setCompoundDrawablesWithIntrinsicBounds(pullSpin, null, null, null);
    }
    private void startPullSpin() {
        ensurePullSpin();
        if (txtStatus == null || pullSpin == null) return;
        txtStatus.setCompoundDrawablesWithIntrinsicBounds(pullSpin, null, null, null);
        main.removeCallbacks(spinRunnable);
        main.post(spinRunnable);
    }
    private void hidePullIcon() {
        main.removeCallbacks(spinRunnable);
        if (txtStatus != null) txtStatus.setCompoundDrawables(null, null, null, null);
    }
    private final View.OnTouchListener pullListener = new View.OnTouchListener() {
        public boolean onTouch(View v, android.view.MotionEvent e) {
            int action = e.getAction();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                pullTopY = isAtTop(v) ? e.getY() : -1;
                return false;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) {
                if (isAtTop(v)) {
                    if (pullTopY < 0) pullTopY = e.getY();
                } else {
                    pullTopY = -1;
                }
                if (pullTopY < 0 || refreshing) return false;
                float pull = e.getY() - pullTopY;
                if (pull <= 0) return false;
                String hint = pull >= pullThresholdPx ? "Thả ra để cập nhật" : "Kéo xuống để cập nhật";
                if (!hint.equals(pullHint)) { pullHint = hint; showStatus(hint); }
                // v1.24: the reload icon turns with the pull.
                showPullIcon(pullThresholdPx > 0 ? pull / pullThresholdPx : 0);
                return false;
            }
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                float pull = pullTopY < 0 ? 0 : e.getY() - pullTopY;
                boolean hadHint = pullHint != null;
                pullTopY = -1;
                pullHint = null;
                if (pull >= pullThresholdPx && !refreshing) {
                    refresh();
                } else if (hadHint) {
                    // Pull cancelled below the threshold: the hint
                    // belongs to this gesture, so hide it at once
                    // instead of lingering 5s after the gesture
                    // ended. (If no hint was shown — a plain tap
                    // or scroll — the status strip is left alone,
                    // so a previous "Đã cập nhật…" line still gets
                    // its full 5s.)
                    hidePullIcon();
                    if (txtStatus != null) {
                        txtStatus.removeCallbacks(hideStatusRunnable);
                        txtStatus.setVisibility(View.GONE);
                        syncContentTopPadding();
                    }
                }
                return false;
            }
            return false;
        }
        private boolean isAtTop(View v) {
            // v3.23: AbsListView, not ListView — the 3 lists are
            // GridViews now (2 columns in landscape), and a
            // GridView is not a ListView; the old check would
            // have silently killed pull-to-refresh on all 3 tabs.
            if (v instanceof android.widget.AbsListView) {
                android.widget.AbsListView lv = (android.widget.AbsListView) v;
                if (lv.getFirstVisiblePosition() != 0) return false;
                View first = lv.getChildAt(0);
                // Empty list: no child, trivially at the top.
                // Otherwise the first row must sit at (or past,
                // during overscroll) the content top, i.e. at or
                // below the list's own top padding — a row
                // part-way off the top (top < padding) means the
                // list is scrolled and can still scroll up.
                return first == null || first.getTop() >= lv.getPaddingTop();
            }
            if (v instanceof android.widget.ScrollView) {
                return ((android.widget.ScrollView) v).getScrollY() == 0;
            }
            return false;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v1.7: prefs renamed rightnews -> kenzelections (old name was
        // unrelated to the app's current name). Migrate once so an
        // install-over keeps sources, glass/theme, notification and
        // translation settings instead of resetting them.
        migratePrefs();
        if (savedInstanceState != null) tab = savedInstanceState.getInt("tab", 0);
        setContentView(R.layout.activity_main);
        Theme.preferHighRefreshRate(this);
        applyInsets();
        TranslateUtil.loadTitleCache(this);
        sources = Sources.load(this);
        sourcesSig = calcSourcesSig();
        txtStatus = findViewById(R.id.txtStatus);
        txtVideoHead = findViewById(R.id.txtVideoHead);
        txtVideoNote = findViewById(R.id.txtVideoNote);
        txtLiveHead = findViewById(R.id.txtLiveHead);
        liveContainer = findViewById(R.id.liveContainer);
        liveStickerRow = findViewById(R.id.liveStickerRow);
        txtAppTitle = findViewById(R.id.txtAppTitle);
        requestMenuBlur();
        bottomBlur = findViewById(R.id.bottomBlur);
        bottomBlur.setSource(findViewById(R.id.contentRoot));
        topBlur = findViewById(R.id.topBlur);
        if (topBlur != null) topBlur.setSource(findViewById(R.id.contentRoot));
        // v1.7: re-blur the menu strip whenever any list scrolls, so
        // the blurred content behind the menu tracks what is under it.
        // v3.17: this listener alone is NOT enough — the video list's
        // own scroll listener (below) replaces it, the lottery tab
        // scrolls in a ScrollView (no OnScrollListener at all), and
        // the saved list never got one: on those 3 tabs the menu
        // blur froze at the tab-switch snapshot while the content
        // kept scrolling under it (his 13:36 video: the bar shows
        // a ghost of old content on Video/Xo so/Da luu, live blur
        // only on Tin moi). All three are wired below.
        android.widget.AbsListView.OnScrollListener blurScroll = new android.widget.AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView v, int s) { if (s == SCROLL_STATE_IDLE) showBars(); }
            public void onScroll(android.widget.AbsListView v, int a, int b, int c) { requestBothBlur(); onBarsScrolled(v); }
        };
        ((GridView) findViewById(R.id.listNews)).setOnScrollListener(blurScroll);
        // (listVideo is NOT wired here: its own scroll listener
        // below replaces any listener set here — the blur is
        // re-requested inside that listener instead.)
        // v3.0: pull-to-refresh on all three scroll views. The
        // listener only observes (always returns false), so list
        // scrolling, row taps and the Sources swipe keep working
        // exactly as before — it never consumes a touch.
        pullThresholdPx = 120f * getResources().getDisplayMetrics().density;
        View pullNews = findViewById(R.id.listNews);
        if (pullNews != null) pullNews.setOnTouchListener(pullListener);
        View pullVideo = findViewById(R.id.listVideo);
        if (pullVideo != null) pullVideo.setOnTouchListener(pullListener);
        newsBox = findViewById(R.id.newsBox);
        videoBox = findViewById(R.id.videoBox);
        savedBox = findViewById(R.id.savedBox);
        lotteryBox = findViewById(R.id.lotteryBox);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        headerBar = findViewById(R.id.headerBar);
        applyTheme();

        newsAdapter = new NewsAdapter(this, new ArrayList<NewsItem>());
        videoAdapter = new NewsAdapter(this, new ArrayList<NewsItem>());
        savedAdapter = new NewsAdapter(this, new ArrayList<NewsItem>());
        compactAtCreate = Theme.compactView(this);
        fontAtCreate = Theme.fontMode(this);
        GridView lvNews = findViewById(R.id.listNews);
        lvNews.setAdapter(newsAdapter);
        lvNews.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) { openLink(((NewsItem) newsAdapter.getItem(p)).link); }
        });
        lvNews.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int p, long id) { saveItem((NewsItem) newsAdapter.getItem(p)); return true; }
        });
        // v3.35: Breaking News banner — tap opens the breaking story.
        View breakingBar = findViewById(R.id.breakingBar);
        if (breakingBar != null) breakingBar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (breakingItem != null) openLink(breakingItem.link); }
        });
        // v3.35: dismiss — X hides the banner. The dismissed story's
        // key is saved, so it stays hidden across refreshes and app
        // restarts; a NEW breaking story (different key) shows the
        // banner again.
        View breakingClose = findViewById(R.id.btnBreakingClose);
        if (breakingClose != null) breakingClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (breakingItem != null) {
                    getSharedPreferences("kenzelections", MODE_PRIVATE).edit()
                            .putString("breaking_dismissed_key", breakingItem.key()).apply();
                    updateBreakingBanner();
                }
            }
        });
        buildCatChips();
        GridView lvVi = findViewById(R.id.listVideo);
        lvVi.setAdapter(videoAdapter);
        lvVi.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) { openVideo((NewsItem) videoAdapter.getItem(p)); }
        });
        lvVi.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int p, long id) { saveItem((NewsItem) videoAdapter.getItem(p)); return true; }
        });
        // v1.26: scrolling the video list DOWN collapses any
        // expanded LIVE detail (the stickers stay), so the
        // expanded card never sits on top of the videos the
        // user is scrolling to. Scrolling up never expands —
        // the user re-opens a detail by tapping its sticker.
        // This is a scroll listener, separate from the v1.23
        // pull-to-refresh touch observer, so the two coexist.
        lvVi.setOnScrollListener(new AbsListView.OnScrollListener() {
            int prevFirst = 0; int prevTop = 0;
            public void onScrollStateChanged(AbsListView v, int s) { if (s == SCROLL_STATE_IDLE) showBars(); }
            public void onScroll(AbsListView v, int first, int vis, int total) {
                View child = v.getChildAt(0);
                int top = child == null ? 0 : child.getTop();
                boolean down = first > prevFirst || (first == prevFirst && top < prevTop);
                prevFirst = first; prevTop = top;
                if (down && expandedLiveId != null) collapseLive();
                // v3.17: this listener replaced blurScroll on this
                // list (a ListView holds one scroll listener) —
                // re-request the blur here or the video tab's
                // menu freezes (see the blurScroll note above).
                requestBothBlur();
                onBarsScrolled(v);
            }
        });
        findViewById(R.id.tabNews).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(0); } });
        findViewById(R.id.tabVideo).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(1); } });
        findViewById(R.id.tabLottery).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(2); } });
        findViewById(R.id.tabSaved).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(3); } });
        lotteryDate = midnightToday();
        setupLottery();
        // v3.17: the lottery tab scrolls in a ScrollView, which has
        // no OnScrollListener — wire its scroll-change callback to
        // the blur so the menu tracks the results (see blurScroll).
        android.widget.ScrollView lotScroll = findViewById(R.id.lotScroll);
        if (lotScroll != null) lotScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                requestBothBlur();
                if (y > (int) (32 * getResources().getDisplayMetrics().density)) hideBars();
                v.removeCallbacks(lotIdleRunnable);
                v.postDelayed(lotIdleRunnable, 150);
            }
        });
        GridView lvSaved = findViewById(R.id.listSaved);
        lvSaved.setAdapter(savedAdapter);
        // v3.17: the saved list never had the blur scroll listener
        // (see blurScroll) — its menu blur froze like the others.
        lvSaved.setOnScrollListener(blurScroll);
        lvSaved.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) { openSavedItem((NewsItem) savedAdapter.getItem(p)); }
        });
        lvSaved.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int p, long id) {
                NewsItem it = (NewsItem) savedAdapter.getItem(p);
                if (savedMode == 1) {
                    // Nightly: in history mode, long-press removes
                    // that one article from the history.
                    ReadHistory.remove(MainActivity.this, it.key());
                    refreshSavedList();
                    Toast.makeText(MainActivity.this, "Đã xoá khỏi lịch sử: " + it.title, Toast.LENGTH_SHORT).show();
                } else {
                    NewsStore.toggleSaved(MainActivity.this, it);
                    refreshSavedList();
                    Toast.makeText(MainActivity.this, "Đã bỏ lưu: " + it.title, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // Nightly: Saved / History toggle.
        findViewById(R.id.btnSavedTab).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { savedMode = 0; refreshSavedList(); }
        });
        findViewById(R.id.btnHistoryTab).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { savedMode = 1; refreshSavedList(); }
        });
        findViewById(R.id.btnClearHistory).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearHistoryConfirm(); }
        });
        refreshSavedList();
        setupSearch();
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (tab == 2) refreshLottery(true); else refresh(); } });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });

        // Show cache instantly, then fetch live.
        all = NewsStore.loadCache(this);
        updateLists("Đã tải từ bộ nhớ đệm (" + all.size() + " tin) — đang cập nhật…");
        // v3.8: opened from a lottery notification — jump
        // straight to the lottery tab on that region.
        if (getIntent() != null && getIntent().getIntExtra("open_tab", -1) == 2) {
            lotteryRegion = getIntent().getIntExtra("lottery_region", LotteryFetcher.MN);
            tab = 2;
        }
        showTab(tab);
        refresh();
        // v3.12: the job runs when news notify is on OR when
        // keyword alerts exist (see NewsJobService.ensureScheduled).
        NewsJobService.ensureScheduled(this);
        // v3.8: (re)schedule the daily lottery draw alarms.
        LotteryNotifier.scheduleAll(this);
        maybeAskPermissions();
        // v3.23: landscape for the whole app — apply the
        // orientation layout once at startup (and on every
        // rotation via onConfigurationChanged below).
        applyOrientationLayout();
        // v3.28: round the 4 corners of the news display area.
        roundListCorners(findViewById(R.id.listNews));
        roundListCorners(findViewById(R.id.listVideo));
        roundListCorners(findViewById(R.id.listSaved));
        roundListCorners(findViewById(R.id.lotScroll));
    }
    // v3.28: the lists/ScrollView clip their scrolling
    // content to a 24dp rounded rect (the outline follows
    // the view's size, so rotation needs no extra work).
    private void roundListCorners(View v) {
        if (v == null) return;
        final float r = 24 * getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }
    // v3.23: landscape for the whole app. Until now the app
    // already rotated (no portrait lock) but every tab just
    // stretched its portrait layout: one full-width column on
    // a wide, short screen. Now the 3 lists become 2-column
    // grids in landscape (same adapter and wiring — GridView
    // is an AbsListView, so scroll/touch/click listeners carry
    // over), the tab boxes lose some of their 104dp top
    // padding (the floating header is the same height, so the
    // clearance can shrink), and the single lottery card is
    // centred at max 640dp instead of stretching edge to edge.
    // Portrait restores 1 column / full width / 104dp.
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientationLayout();
    }
    private void applyOrientationLayout() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int cols = landscape ? 2 : 1;
        float d = getResources().getDisplayMetrics().density;
        GridView lvNews = findViewById(R.id.listNews);
        if (lvNews != null) lvNews.setNumColumns(cols);
        GridView lvVi = findViewById(R.id.listVideo);
        if (lvVi != null) lvVi.setNumColumns(cols);
        GridView lvSaved = findViewById(R.id.listSaved);
        if (lvSaved != null) lvSaved.setNumColumns(cols);
        // v3.24: rebind the rows so the adapter picks the
        // orientation's row layout (compact in landscape, the
        // "Dạng xem tin" setting in portrait) — changing the
        // column count alone re-lays out but does not rebind.
        if (newsAdapter != null) newsAdapter.notifyDataSetChanged();
        if (videoAdapter != null) videoAdapter.notifyDataSetChanged();
        if (savedAdapter != null) savedAdapter.notifyDataSetChanged();
        // v3.24: bottom menu goes icon-only in landscape —
        // labels hidden, bar 64dp -> 48dp, tabs 56dp -> 40dp —
        // so the short screen keeps 16dp more content above
        // it. Labels are restored in portrait; setTab never
        // touches the text, so hiding it cannot fight the
        // selection style.
        String[] tabLabels = {"Tin mới", "Video", "Xổ số", "Đã lưu"};
        int[] tabIds = {R.id.tabNews, R.id.tabVideo, R.id.tabLottery, R.id.tabSaved};
        View bottomBar = findViewById(R.id.bottomBar);
        if (bottomBar != null) {
            ViewGroup.LayoutParams blp = bottomBar.getLayoutParams();
            if (blp != null) { blp.height = (int) ((landscape ? 48 : 64) * d); bottomBar.setLayoutParams(blp); }
        }
        for (int i = 0; i < tabIds.length; i++) {
            TextView t = findViewById(tabIds[i]);
            if (t == null) continue;
            t.setText(landscape ? "" : tabLabels[i]);
            ViewGroup.LayoutParams tlp = t.getLayoutParams();
            if (tlp != null) { tlp.height = (int) ((landscape ? 40 : 56) * d); t.setLayoutParams(tlp); }
        }
        layoutLotteryForOrientation(landscape);
        int topPad = (int) ((landscape ? 72 : 104) * d);
        View[] boxes = {newsBox, videoBox, savedBox, lotteryBox};
        for (View b : boxes) if (b != null) b.setPadding(0, topPad, 0, 0);
        View lotScroll = findViewById(R.id.lotScroll);
        if (lotScroll != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) lotScroll.getLayoutParams();
            if (lp != null) {
                if (landscape) {
                    int w = getResources().getDisplayMetrics().widthPixels;
                    lp.width = Math.min(w, (int) (640 * d));
                    lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                } else {
                    lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                    lp.gravity = android.view.Gravity.NO_GRAVITY;
                }
                lotScroll.setLayoutParams(lp);
            }
        }
        // v3.26: rotation resets the bars to visible (see
        // the note on hideBars/showBars above).
        showBars();
        requestBothBlur();
    }
    // v3.24: the lottery tab could not scroll at all in
    // landscape — the fixed rows (chips, date, status, dò,
    // station tabs) + top padding left the ScrollView a ~17dp
    // strip, entirely behind the bottom menu, so there was
    // nothing to grab. In landscape the control rows move
    // inside the ScrollView (above the cards), so the whole
    // tab scrolls as one page and the card gets the full
    // height once the controls scroll away. Portrait puts
    // them back above the ScrollView, fixed, as before.
    // The moves are idempotent (checked by parent) and the
    // 10s poll only clears lotCards, never the moved rows.
    private void layoutLotteryForOrientation(boolean landscape) {
        View lotteryBox = findViewById(R.id.lotteryBox);
        View controlsBox = findViewById(R.id.lotControlsBox);
        if (lotteryBox == null || controlsBox == null || !(lotteryBox instanceof ViewGroup) || !(controlsBox instanceof ViewGroup)) return;
        int[] rowIds = {R.id.lotRow, R.id.lotDateRow, R.id.txtLotStatus, R.id.lotCheckRow, R.id.lotStationScroll};
        ViewGroup from = landscape ? (ViewGroup) lotteryBox : (ViewGroup) controlsBox;
        ViewGroup to = landscape ? (ViewGroup) controlsBox : (ViewGroup) lotteryBox;
        for (int i = 0; i < rowIds.length; i++) {
            View row = findViewById(rowIds[i]);
            if (row == null || row.getParent() == to) continue;
            if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).removeView(row);
            if (landscape) to.addView(row);
            else to.addView(row, i);
        }
    }
    // v1.38 + v3.29: first-launch permission prompt (his
    // order): on the first open after a fresh install, ask
    // for the three system accesses the app needs to run
    // smoothly, in one guided walk instead of three separate
    // surprises: (1) Notifications (runtime, API 33+) — news,
    // lottery and the playback notification; (2) Ignore
    // battery optimizations — without it Doze can starve the
    // background player of network when the screen is off;
    // (3) Picture-in-picture (a SYSTEM special access, not a
    // runtime permission, so it cannot be asked with a runtime
    // dialog — the system's PiP settings page opens last).
    // Only the accesses actually missing are listed and
    // walked; if everything is already on, no dialog appears
    // at all. Shown once ever (pref perm_prompt_v1); "Để sau"
    // only dismisses it, and the Settings "Quyền hệ thống"
    // row stays as the permanent path back.
    private boolean permNeedNotif, permNeedBattery, permNeedPip;
    private void maybeAskPermissions() {
        android.content.SharedPreferences p = getSharedPreferences("kenzelections", MODE_PRIVATE);
        if (p.getBoolean("perm_prompt_v1", false)) return;
        p.edit().putBoolean("perm_prompt_v1", true).apply();
        permNeedNotif = Build.VERSION.SDK_INT >= 33 && !Perms.notificationsOn(this);
        permNeedBattery = !Perms.batteryOptIgnored(this);
        permNeedPip = !Perms.pipAllowed(this);
        if (!permNeedNotif && !permNeedBattery && !permNeedPip) return;
        StringBuilder msg = new StringBuilder("Để Kenz News chạy trơn tru, app cần:\n");
        if (permNeedNotif) msg.append("• Quyền Thông báo — báo tin mới, xổ số và hiện trình phát.\n");
        if (permNeedBattery) msg.append("• Bỏ qua tối ưu pin — để nhạc/video không bị tắt khi tắt màn hình.\n");
        if (permNeedPip) msg.append("• Quyền Hình trong hình — để thu nhỏ video khi rời app.\n");
        msg.append("\nBấm \"Cấp quyền\" rồi làm theo từng bước hiện ra.");
        AlertDialog _dlg = new AlertDialog.Builder(this, Theme.dialogTheme(this))
                .setTitle("Quyền cần thiết")
                .setMessage(msg.toString())
                .setPositiveButton("Cấp quyền", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { runNextPermStep(); }
                })
                .setNegativeButton("Để sau", null)
                .show();
        Theme.styleDialog(_dlg, this);
    }
    // Walks the still-missing steps in order. A deny, a Back
    // press or a cancel still advances — the user skips that
    // step instead of stalling the chain.
    private void runNextPermStep() {
        if (permNeedNotif) {
            permNeedNotif = false;
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
                return;
            }
        }
        if (permNeedBattery) {
            permNeedBattery = false;
            Perms.openBatteryOptRequest(this);
            return;
        }
        if (permNeedPip) {
            permNeedPip = false;
            Perms.openPipSettings(this);
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9001) runNextPermStep();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9005) runNextPermStep();
    }
    private void migratePrefs() {
        try {
            android.content.SharedPreferences nw = getSharedPreferences("kenzelections", MODE_PRIVATE);
            if (nw.getBoolean("migrated_from_rightnews", false)) return;
            android.content.SharedPreferences old = getSharedPreferences("rightnews", MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = nw.edit();
            for (Map.Entry<String, ?> en : old.getAll().entrySet()) {
                Object v = en.getValue();
                if (v instanceof String) e.putString(en.getKey(), (String) v);
                else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
                else if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
                else if (v instanceof Float) e.putFloat(en.getKey(), (Float) v);
                else if (v instanceof Set) e.putStringSet(en.getKey(), (Set<String>) v);
            }
            e.putBoolean("migrated_from_rightnews", true);
            e.apply();
        } catch (Exception ignored) {}
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("tab", tab);
    }
    @Override protected void onPause() {
        super.onPause();
        // v3.3: stop the live lottery poll while the app is
        // in the background; onResume (via showTab) re-arms it.
        lotteryPollHandler.removeCallbacks(lotteryPollRunnable);
        stopRolling(); // v3.32: rolling numbers too.
        // v3.6: the live-dot blinkers are infinite — cancel
        // them here (onResume re-styles the chips and restarts
        // them) so they cannot outlive the activity.
        stopLiveDotAnims();
    }
    @Override protected void onResume() {
        super.onResume();
        // Settings may have changed glass mode, title translation,
        // theme, sources, or asked to open a tab — apply them on return.
        applyTheme();
        // v3.12: sources may have changed in Settings — reload
        // + refresh when the enabled set differs.
        List<Source> reloaded = Sources.load(this);
        String sig = "";
        for (Source sc : reloaded) sig += sc.name + (sc.enabled ? "1" : "0") + ";";
        if (!sig.equals(sourcesSig)) { sources = reloaded; sourcesSig = sig; refresh(); }
        int openTab = getSharedPreferences("kenzelections", MODE_PRIVATE).getInt("open_tab", -1);
        if (openTab >= 0) {
            getSharedPreferences("kenzelections", MODE_PRIVATE).edit().remove("open_tab").apply();
            showTab(openTab);
        } else showTab(tab);
        if (newsAdapter != null) newsAdapter.notifyDataSetChanged();
        if (videoAdapter != null) videoAdapter.notifyDataSetChanged();
        // v3.1: a Settings change to view mode or font size needs
        // new adapter instances (compact is a different layout);
        // the saved list is reloaded too.
        if (compactAtCreate != Theme.compactView(this) || fontAtCreate != Theme.fontMode(this)) {
            compactAtCreate = Theme.compactView(this);
            fontAtCreate = Theme.fontMode(this);
            newsAdapter = new NewsAdapter(this, new ArrayList<NewsItem>());
            videoAdapter = new NewsAdapter(this, new ArrayList<NewsItem>());
            savedAdapter = new NewsAdapter(this, new ArrayList<NewsItem>());
            GridView lvN = findViewById(R.id.listNews);
            if (lvN != null) lvN.setAdapter(newsAdapter);
            GridView lvV = findViewById(R.id.listVideo);
            if (lvV != null) lvV.setAdapter(videoAdapter);
            GridView lvS = findViewById(R.id.listSaved);
            if (lvS != null) lvS.setAdapter(savedAdapter);
            applyNewsFilter();
            videoAdapter.setItems(new ArrayList<>(videoNews));
        }
        refreshSavedList();
        startTitleTranslation();
        // Nightly OTA: once per process, show the changelog dialog
        // when a newer nightly exists (toggle lives in Settings).
        UpdateChecker.checkOnLaunch(this);
    }
    // v1.11 (suggestion 6): translate what is on screen first.
    // The queue is in list order, so handing it the whole list
    // already put the top cards first — but only for the news
    // tab. On the election/video tabs the visible cards are
    // scattered through `all`, so they waited behind unrelated
    // cards. Visible cards now go first, then the rest.
    private List<NewsItem> orderedForTranslation() {
        // v3.0: only the Video tab (tab 1) has its own
        // visible list; the News tab shows `all`.
        List<NewsItem> visible = tab == 1 ? videoNews : all;
        List<NewsItem> ordered = new ArrayList<>(visible);
        Set<String> seen = new HashSet<>();
        for (NewsItem it : visible) if (it != null) seen.add(it.key());
        for (NewsItem it : all) if (it != null && !seen.contains(it.key())) ordered.add(it);
        return ordered;
    }
    private void startTitleTranslation() {
        if (!TranslateUtil.translateTitlesEnabled(this)) return;
        final android.content.Context app = getApplicationContext();
        TranslateUtil.translateTitles(app, orderedForTranslation(), new Runnable() {
            public void run() {
                if (newsAdapter != null) newsAdapter.notifyDataSetChanged();
                        if (videoAdapter != null) videoAdapter.notifyDataSetChanged();
                updateBreakingBanner();
            }
        });
    }
    // v1.4: the app draws behind the status bar (gradient background),
    // so content must be padded by the real system insets, not a fixed
    // 12dp — that fixed padding is exactly why the status bar sat on top
    // of the top glass bar in the 22:34 screenshot.
    private void applyInsets() {
        final View root = findViewById(R.id.rootLayout);
        final View content = findViewById(R.id.contentRoot);
        final View bottom = findViewById(R.id.bottomBar);
        final View header = findViewById(R.id.headerBar);
        final float d = getResources().getDisplayMetrics().density;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bot = insets.getSystemWindowInsetBottom();
                // v1.12: the header is a floating blur bar like
                // the menu, so content starts at the top, UNDER
                // it — the news list's own 104dp top padding (with
                // clipToPadding off) puts the first card below
                // the header + status strip on first open, and
                // lets cards scroll up underneath the header blur
                // exactly the way they already scroll underneath
                // the bottom menu. The status strip below is
                // positioned here too (header 56dp + 8dp gaps).
                content.setPadding((int) (12 * d), top + (int) (8 * d), (int) (12 * d), (int) (12 * d));
                View status = findViewById(R.id.txtStatus);
                if (status != null) {
                    FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) status.getLayoutParams();
                    sp.topMargin = top + (int) (72 * d);
                    sp.leftMargin = (int) (12 * d);
                    sp.rightMargin = (int) (12 * d);
                    status.setLayoutParams(sp);
                }
                if (header != null) {
                    FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) header.getLayoutParams();
                    hp.topMargin = top + (int) (8 * d);
                    hp.leftMargin = (int) (12 * d);
                    hp.rightMargin = (int) (12 * d);
                    header.setLayoutParams(hp);
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bottom.getLayoutParams();
                lp.bottomMargin = bot + (int) (8 * d);
                lp.leftMargin = (int) (12 * d);
                lp.rightMargin = (int) (12 * d);
                bottom.setLayoutParams(lp);
                return insets;
            }
        });
    }
    private void applyGlass() {
        // v1.10: topBar sits on topBlur — do NOT applyBar here, a
        // tint drawable on topBar would cover the header blur
        // exactly as it would have covered the menu blur.
        if (topBar != null) topBar.setBackgroundColor(0x00000000);
        if (headerBar != null) {
            headerBar.setElevation(6f * getResources().getDisplayMetrics().density);
            headerBar.setClipToOutline(false);
        }
        if (topBlur != null) topBlur.requestBlur();
        // v1.7: do NOT applyBar to bottomBar any more — its background
        // is BlurView (blurred news + tint). Only elevation + front.
        bottomBar.setElevation(6f * getResources().getDisplayMetrics().density);
        bottomBar.setClipToOutline(false);
        bottomBar.bringToFront();
        if (bottomBlur != null) bottomBlur.requestBlur();
        // v1.5: Glass mode moved to Settings, so there is no btnGlass
        // in the header any more. Nothing here may touch a tab
        // background except showTab().
        if (newsAdapter != null) newsAdapter.notifyDataSetChanged();
        if (videoAdapter != null) videoAdapter.notifyDataSetChanged();
    }
    // v1.7: Sáng / Tối. Called on create and on resume (Settings may
    // have switched the theme while this activity was behind it).
    private void applyTheme() {
        // v3.9: status bar + navigation bar follow the theme.
        Theme.applyStatusBar(this);
        findViewById(R.id.rootLayout).setBackground(Theme.bgDrawable(this));
        // v1.10: header background is topBlur (see applyGlass),
        // not a tint drawable — keep this line out, it would
        // cover the blur.
        if (topBar != null) topBar.setBackgroundColor(0x00000000);
        if (txtAppTitle != null) txtAppTitle.setTextColor(Theme.headerText(this));
        if (txtStatus != null) {
            txtStatus.setTextColor(Theme.subText(this));
            // v1.12: solid pill behind the status strip so it
            // stays readable when news cards scroll underneath
            // it (black pill in dark mode, white in light mode).
            android.graphics.drawable.GradientDrawable sd = new android.graphics.drawable.GradientDrawable();
            sd.setColor(Theme.isLight(this) ? 0xFFFFFFFF : 0xFF000000);
            sd.setCornerRadius(16f * getResources().getDisplayMetrics().density);
            sd.setStroke(1, Theme.isLight(this) ? 0x330A1B4A : 0x66FFFFFF);
            txtStatus.setBackground(sd);
            // v1.24: keep the pull icon tinted to the new theme.
            if (pullSpinInner != null) pullSpinInner.setTint(Theme.subText(this));
        }
        // v1.13: every section head (video, sources) uses
        // the same header colour + bold 13sp.
        if (txtVideoHead != null) txtVideoHead.setTextColor(Theme.headerText(this));
        if (txtVideoNote != null) txtVideoNote.setTextColor(Theme.noteText(this));
        if (txtLiveHead != null) txtLiveHead.setTextColor(Theme.headerText(this));
        // v1.26: repaint LIVE stickers with the new theme
        // colours (a theme switch must not leave dark cards on
        // a light theme). Skipped before the first check lands
        // (liveChannels null); it also collapses any expanded
        // detail, which is the safe state after a theme change.
        if (liveChannels != null) updateLiveSection(liveChannels);
        // v1.24: cardBg, same dark-mode fix as the news cards
        // (glass() over black is dark grey; cardBg is solid slate
        // and the theme text above is already light in dark mode,
        // so contrast goes from ~5.8:1 to ~11:1. Light mode is
        // visually identical — see the NewsAdapter comment.)
        ImageView btnSettings = findViewById(R.id.btnSettings);
        ImageView btnRefresh = findViewById(R.id.btnRefresh);
        // v1.12: header buttons are plain text/icons on the
        // blur, like the menu's unselected tabs — no chip pill
        // inside the header pill. That is what makes the header
        // read as fully transparent glass instead of a solid bar
        // with solid buttons on it. (Other screens keep their
        // chip buttons; only the main header goes plain.)
        if (btnSettings != null) { btnSettings.setBackgroundColor(0x00000000); btnSettings.setColorFilter(Theme.headerText(this), android.graphics.PorterDuff.Mode.SRC_IN); }
        // v1.23: the refresh icon is a white vector; tint it to
        // the theme's header text (white in dark mode, navy in
        // light mode) so it stays visible on both header
        // backgrounds — a fixed white icon would vanish on the
        // light header. Background stays transparent, like the gear.
        if (btnRefresh != null) { btnRefresh.setBackgroundColor(0x00000000); btnRefresh.setColorFilter(Theme.headerText(this), android.graphics.PorterDuff.Mode.SRC_IN); }
        ImageView btnSearch = findViewById(R.id.btnSearch);
        if (btnSearch != null) { btnSearch.setBackgroundColor(0x00000000); btnSearch.setColorFilter(Theme.headerText(this), android.graphics.PorterDuff.Mode.SRC_IN); }
        // v3.1: search row + sources hint + saved empty text
        // follow the theme (the row's card background is set
        // here too — glass_card would be the wrong tint).
        View searchBox = findViewById(R.id.searchBox);
        if (searchBox != null) searchBox.setBackground(GlassUi.cardBg(this, 28f));
        EditText edtSearch = findViewById(R.id.edtSearch);
        if (edtSearch != null) { edtSearch.setTextColor(Theme.bodyText(this)); edtSearch.setHintTextColor(Theme.noteText(this)); }
        TextView sc = findViewById(R.id.txtSearchCount);
        if (sc != null) sc.setTextColor(Theme.noteText(this));
        TextView sclr = findViewById(R.id.btnSearchClear);
        if (sclr != null) sclr.setTextColor(Theme.subText(this));
        TextView se = findViewById(R.id.txtSavedEmpty);
        if (se != null) se.setTextColor(Theme.noteText(this));
        // v3.3: lottery tab follows the theme; the cards
        // rebuild from cache on the showTab call below.
        TextView lotStatus = findViewById(R.id.txtLotStatus);
        if (lotStatus != null) lotStatus.setTextColor(Theme.noteText(this));
        TextView lotDate = findViewById(R.id.txtLotDate);
        if (lotDate != null) lotDate.setTextColor(Theme.headerText(this));
        int[] lotNav = {R.id.btnLotPrev, R.id.btnLotNext};
        for (int id : lotNav) {
            TextView b = findViewById(id);
            if (b != null) b.setTextColor(Theme.subText(this));
        }
        // v3.6: the calendar button is a vector icon now, not
        // text — tint it to the theme like the header icons.
        ImageView lotCal = findViewById(R.id.btnLotCal);
        if (lotCal != null) lotCal.setColorFilter(Theme.subText(this));
        // v3.12: lottery check row follows the theme.
        View lotCheck = findViewById(R.id.lotCheckRow);
        if (lotCheck != null) lotCheck.setBackground(GlassUi.cardBg(this, 28f));
        EditText edLot = findViewById(R.id.edtLotCheck);
        if (edLot != null) { edLot.setTextColor(Theme.bodyText(this)); edLot.setHintTextColor(Theme.noteText(this)); }
        TextView btnLotCheck = findViewById(R.id.btnLotCheck);
        if (btnLotCheck != null) { btnLotCheck.setBackground(Theme.pillDrawable(this)); btnLotCheck.setTextColor(Theme.pillText(this)); }
        // Nightly: the stats button is a row (icon + label) now —
        // tint the icon and colour the label so both stay readable
        // in dark mode. The row itself centres icon + label as
        // one unit (see the layout note).
        TextView txtLotStats = findViewById(R.id.txtLotStats);
        if (txtLotStats != null) txtLotStats.setTextColor(Theme.bodyText(this));
        ImageView imgLotStats = findViewById(R.id.imgLotStats);
        if (imgLotStats != null) imgLotStats.setColorFilter(Theme.bodyText(this));
        styleLotteryChips();
        if (bottomBlur != null) bottomBlur.invalidate();
        if (topBlur != null) topBlur.invalidate();
        showTab(tab);
        applyGlass();
    }
    // v1.4 expressive tabs: the selected tab is a solid white pill with
    // navy text; unselected tabs are plain light text on glass. No Button
    // background is ever involved, so no stripe can appear.
    private void showTab(int t) {
        tab = t;
        // v3.32: leaving the lottery tab stops the rolling
        // numbers (returning re-arms them via renderLottery).
        if (t != 2) stopRolling();
        // v1.14: re-blur after the new tab has laid out, so the
        // header/menu never keep a ghost of the previous tab.
        if (bottomBlur != null) bottomBlur.postDelayed(new Runnable() { public void run() { bottomBlur.requestBlur(); } }, 150);
        if (topBlur != null) topBlur.postDelayed(new Runnable() { public void run() { topBlur.requestBlur(); } }, 150);
        newsBox.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        videoBox.setVisibility(t == 1 ? View.VISIBLE : View.GONE);
        lotteryBox.setVisibility(t == 2 ? View.VISIBLE : View.GONE);
        savedBox.setVisibility(t == 3 ? View.VISIBLE : View.GONE);
        setTab(R.id.tabNews, t == 0);
        setTab(R.id.tabVideo, t == 1);
        setTab(R.id.tabLottery, t == 2);
        setTab(R.id.tabSaved, t == 3);
        // v3.3: entering the lottery tab loads its results
        // (from cache when already fetched) and arms the
        // live poll; leaving it disarms the poll.
        // v3.12: lottery is tab 2 now (Sources moved to Settings).
        if (t == 2) { updateLotteryDateLabel(); refreshLottery(false); }
        scheduleLotteryPoll();
        applyTabStatus();
        // v3.27: tab switch brings the bars back and reseeds
        // the movement tracking from the newly shown list.
        showBars();
        int listId = t == 0 ? R.id.listNews : t == 1 ? R.id.listVideo : t == 3 ? R.id.listSaved : 0;
        if (listId != 0) {
            android.widget.AbsListView lv = findViewById(listId);
            if (lv != null) {
                lastBarFirst = lv.getFirstVisiblePosition();
                View fc = lv.getChildAt(0);
                lastBarTop = fc == null ? 0 : fc.getTop();
            }
        }
    }
    // v3.26: the category chips row + the bottom menu hide
    // while scrolling and come back when the scroll stops
    // (his correction of v3.25 — the screenshot circled the
    // chips row and the menu, not the Kenz News header, so
    // the header stays put). The chips row only exists on the
    // news tab; the menu hides on all 4 tabs. The chips row
    // is in-flow (GONE reclaims its ~50dp for the list); the
    // menu is a floating overlay (slides down off-screen).
    // Hide only after ~32dp of scroll, so a tiny jiggle at
    // the top doesn't flicker them.
    private boolean barsHidden = false;
    private Runnable lotIdleRunnable = new Runnable() { public void run() { showBars(); } };
    private Runnable hideChipsRunnable = new Runnable() {
        public void run() {
            if (!barsHidden) return;
            View cats = findViewById(R.id.catScroll);
            if (cats != null) cats.setVisibility(View.GONE);
        }
    };
    private void hideBars() {
        if (barsHidden) return;
        barsHidden = true;
        View cats = findViewById(R.id.catScroll);
        if (cats != null) {
            cats.animate().cancel();
            cats.animate().alpha(0f).setDuration(150).setInterpolator(new AccelerateInterpolator()).start();
            cats.removeCallbacks(hideChipsRunnable);
            cats.postDelayed(hideChipsRunnable, 150);
        }
        if (bottomBar != null) {
            float d = getResources().getDisplayMetrics().density;
            int hh = bottomBar.getHeight() > 0 ? bottomBar.getHeight() : (int) (64 * d);
            bottomBar.animate().cancel();
            bottomBar.animate().translationY(hh + (int) (8 * d)).alpha(0f).setDuration(250).setInterpolator(new AccelerateInterpolator()).start();
        }
    }
    private void showBars() {
        if (!barsHidden) return;
        barsHidden = false;
        View cats = findViewById(R.id.catScroll);
        if (cats != null) {
            cats.removeCallbacks(hideChipsRunnable);
            cats.setVisibility(View.VISIBLE);
            cats.animate().cancel();
            cats.animate().alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (bottomBar != null) {
            bottomBar.animate().cancel();
            bottomBar.animate().translationY(0).alpha(1f).setDuration(250).setInterpolator(new DecelerateInterpolator()).start();
        }
    }
    private int lastBarFirst = 0, lastBarTop = 0;
    private void onBarsScrolled(android.widget.AbsListView lv) {
        int first = lv.getFirstVisiblePosition();
        View c = lv.getChildAt(0);
        int top = c == null ? 0 : c.getTop();
        // v3.27: only a real scroll hides the bars. Toggling
        // the chips row (GONE/VISIBLE) re-lays-out the list,
        // which fires onScroll with the SAME first/top — the
        // old code re-hid the bars on that event, so the
        // showBars() on scroll-stop was undone at once and
        // the bars never came back until a tap (his 15:47
        // screenshot). The 2px dead zone absorbs rounding.
        if (first == lastBarFirst && Math.abs(top - lastBarTop) <= 2) return;
        lastBarFirst = first; lastBarTop = top;
        if (first == 0 && top >= lv.getPaddingTop()) return;
        float d = getResources().getDisplayMetrics().density;
        if (first > 0 || top < lv.getPaddingTop() - (int) (32 * d)) hideBars();
    }
    // v1.42 (T3): the status strip used to talk about news on
    // every tab. Now each tab owns its strip text: News keeps
    // its existing 5s-auto-hide behaviour; the other tabs show
    // their own sticky status, re-applied after every refresh
    // part lands and on every tab switch.
    // v1.45: the content top padding used to be a fixed 104dp
    // reserving room for header + status strip — so whenever
    // the strip hid (News after 5s, any tab after a pull
    // cancel), a ~48dp dead black band sat under the header
    // (the user's 14:55 Video screenshot). Now the padding
    // follows the strip: 104dp while it shows, 76dp while it
    // hides (header bottom + 20dp breathing room). Called
    // from every site that changes the strip's visibility.
    private void syncContentTopPadding() {
        boolean stripOn = txtStatus != null && txtStatus.getVisibility() == View.VISIBLE;
        int top = (int) (getResources().getDisplayMetrics().density * (stripOn ? 104 : 76));
        View v;
        v = findViewById(R.id.newsBox);
        if (v != null) v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
        v = findViewById(R.id.videoBox);
        if (v != null) v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
        v = findViewById(R.id.savedBox);
        if (v != null) v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
        v = findViewById(R.id.lotteryBox);
        if (v != null) v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
    }
    private void applyTabStatus() {
        if (txtStatus == null) return;
        txtStatus.removeCallbacks(hideStatusRunnable);
        if (tab == 0) {
            if (lastNewsStatus == null || lastNewsStatus.isEmpty()) { txtStatus.setVisibility(View.GONE); syncContentTopPadding(); return; }
            txtStatus.setText(lastNewsStatus);
            txtStatus.setVisibility(View.VISIBLE);
            txtStatus.postDelayed(hideStatusRunnable, 5000);
            syncContentTopPadding();
            return;
        }
        String s;
        if (tab == 1) {
            s = videoStatus;
        } else if (tab == 2) {
            // v3.3: the lottery strip shows the lottery status
            // line (set on every fetch), not the news status.
            // v3.12: lottery is tab 2 now.
            s = lotteryStatusLine == null || lotteryStatusLine.isEmpty()
                    ? "Xổ số 3 miền — kết quả từ Minh Ngọc"
                    : lotteryStatusLine;
        } else {
            s = "Đã lưu: " + NewsStore.loadSaved(this).size() + " tin — nhấn giữ một tin để lưu, nhấn giữ ở đây để bỏ lưu";
        }
        txtStatus.setText(s);
        txtStatus.setVisibility(View.VISIBLE);
        syncContentTopPadding();
    }
    private void setTab(int id, boolean selected) {
        TextView tv = findViewById(id);
        if (selected) {
            tv.setBackground(Theme.pillDrawable(this));
            tv.setTextColor(Theme.pillText(this));
        } else {
            tv.setBackgroundColor(0x00000000);
            tv.setTextColor(Theme.tabUnselected(this));
        }
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        // v3.1: the tab icon (drawableTop) tints with the label
        // colour — white on the navy pill, navy/white plain.
        android.graphics.drawable.Drawable[] ds = tv.getCompoundDrawables();
        if (ds[1] != null) {
            android.graphics.drawable.Drawable ic = ds[1].mutate();
            ic.setTint(selected ? Theme.pillText(this) : Theme.tabUnselected(this));
            tv.setCompoundDrawables(null, ic, null, null);
        }
    }
    private void openLink(String url) {
        if (url == null || url.isEmpty()) return;
        // v1.2: read in-app. ArticleActivity keeps a "Mở ngoài" button
        // for the external browser; the list click no longer leaves the app.
        NewsItem current = null;
        for (NewsItem it : all) if (url.equals(it.link)) { current = it; break; }
        openLink(url, current == null ? "" : current.title, current);
    }
    private void openLink(String url, String title, NewsItem current) {
        Intent i = new Intent(this, ArticleActivity.class);
        i.putExtra("url", url);
        String dispTitle = title;
        if (current != null && TranslateUtil.translateTitlesEnabled(this)) {
            String vi = TranslateUtil.titleVi(current.key());
            if (vi != null && !vi.isEmpty()) dispTitle = vi;
        }
        i.putExtra("title", dispTitle);
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "Không mở được bài", Toast.LENGTH_SHORT).show(); }
        // Nightly: opening an article marks it read (dims it in
        // the list and adds it to the reading history).
        if (current != null) ReadHistory.markRead(this, current);
    }
    // v1.16: open a video straight in the YouTube app when it
    // is installed (vnd.youtube deep link, no in-app player any
    // more — the v1.15 landscape player is removed on his order),
    // otherwise in YouTube web (Chrome when installed, else the
    // chooser). A non-YouTube link still falls back to the
    // article reader, so a stray item never dead-ends.
    private void openVideo(NewsItem it) {
        String url = it == null ? null : it.link;
        String id = null;
        if (url != null) {
            int v = url.indexOf("v=");
            if (v >= 0) {
                int end = url.indexOf('&', v + 2);
                if (end < 0) end = url.indexOf('#', v + 2);
                id = url.substring(v + 2, end < 0 ? url.length() : end);
            } else if (url.contains("youtu.be/")) {
                int s = url.indexOf("youtu.be/") + 9;
                int end = url.indexOf('?', s);
                if (end < 0) end = url.indexOf('#', s);
                id = url.substring(s, end < 0 ? url.length() : end);
            }
        }
        if (id == null || id.isEmpty()) { openLink(url); return; }
        // v1.45: video rows open the app's own player (like LIVE
        // rows already did) instead of jumping to the YouTube
        // app — the player carries the comments section below
        // for non-live videos, and its YouTube button still
        // jumps out to the YouTube app on demand.
        Intent p = new Intent(this, LivePlayerActivity.class);
        p.putExtra("videoId", id);
        p.putExtra("channel", it == null ? "" : it.source);
        p.putExtra("title", it == null ? "" : it.title);
        p.putExtra("channelId", it == null || it.channelId == null ? "" : it.channelId);
        startActivity(p);
    }
    // v1.15: the Video tab's sources — the same five outlets as the
    // news tab, but their YouTube channels, via YouTube's public
    // channel RSS (no API key needed). Channel IDs verified live
    // 2026-11-04: each feed returned its outlet's own name and
    // recent videos with thumbnails. RssFetcher already parses
    // Atom <entry> feeds, so no new parser was needed.
    // Feed URLs look like .../feeds/videos.xml?channel_id=UC...
    // — the fallback needs the bare channel id for the /videos
    // page. Returns null when the URL has no usable id.
    private static String channelIdFromFeedUrl(String url) {
        if (url == null) return null;
        int i = url.indexOf("channel_id=");
        if (i < 0) return null;
        String id = url.substring(i + 11);
        int amp = id.indexOf('&');
        if (amp >= 0) id = id.substring(0, amp);
        return id.matches("[A-Za-z0-9_-]{22,24}") ? id : null;
    }
    private List<Source> youtubeSources() {
        List<Source> y = new ArrayList<>();
        y.add(new Source("Fox News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCXIJgqnII2ZOINSWNOGFThA"));
        y.add(new Source("Breitbart", "https://www.youtube.com/feeds/videos.xml?channel_id=UCDOIXHGP4D7GMtYvnZ0ldwQ"));
        y.add(new Source("New York Post", "https://www.youtube.com/feeds/videos.xml?channel_id=UCrvhNP_lWuPIP6QZzJmM-bw"));
        y.add(new Source("Daily Caller", "https://www.youtube.com/feeds/videos.xml?channel_id=UCP4BdwPKyYnfRLjSNfGAPRA"));
        y.add(new Source("Washington Examiner", "https://www.youtube.com/feeds/videos.xml?channel_id=UC8BxSGcBKriJvoeyKOnJ6tA"));
        // v1.30: CNN, CBS, NBC YouTube channels, on his order.
        // IDs verified 2026-11-04 via direct channel URLs
        // (each returned its own name + self-matching canonical;
        // the @CNN handle itself returns empty from this VM, so
        // the ID URL is the verified form, not the handle). The
        // channel RSS feeds themselves could NOT be verified
        // from the VM — YouTube feeds return 404/500 for this
        // datacenter IP, same as the original five — but the
        // phone's mobile network is unaffected, and a failing
        // feed only shows its error in tab Nguồn.
        y.add(new Source("CNN", "https://www.youtube.com/feeds/videos.xml?channel_id=UCupvZG-5ko_eiXAupbDfxWw"));
        y.add(new Source("CBS News", "https://www.youtube.com/feeds/videos.xml?channel_id=UC8p1vwvWtl6T73JiExfWs1g"));
        y.add(new Source("NBC News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCeY0bbntWzzVIaj2z3QigXg"));
        // v1.31: Sean Le TV, on his order. Disambiguation, done
        // 2026-11-04: two channels share the name — "Sean Le TV"
        // (UCFKux_Nnxx2bOLZsoAk0-pw, the Vietnamese-language US
        // politics live streamer from every search result) is the
        // one added; "Sean Le (SLE)" (UCk1QCzEbsDHMxClgH8iMf1g)
        // has no content at all and is NOT this channel. Channel
        // RSS again unverifiable from the VM (YouTube feeds block)
        // — phone network unaffected, failing feed only shows
        // its error in tab Nguồn.
        y.add(new Source("Sean Le TV", "https://www.youtube.com/feeds/videos.xml?channel_id=UCFKux_Nnxx2bOLZsoAk0-pw"));
        // v3.0: general news channels (ABC, AP, Reuters), so the
        // Video tab carries news videos, not just election ones.
        // IDs verified 2026-11-17 via m.youtube.com/videos
        // (31 video blocks each); the RSS feeds themselves are
        // unverifiable from the VM like all YouTube feeds.
        y.add(new Source("ABC News", "https://www.youtube.com/feeds/videos.xml?channel_id=UCBi2mrWuNuyYy4gbM6fU18Q"));
        y.add(new Source("Associated Press", "https://www.youtube.com/feeds/videos.xml?channel_id=UC52X5wxOL_s5yw0dQk7NtgA"));
        y.add(new Source("Reuters", "https://www.youtube.com/feeds/videos.xml?channel_id=UChqUTb7kYRX8-EiaN3XFrSQ"));
        return y;
    }
    private void updateLists(String status) {
        showStatus(status);
        applyNewsFilter();
        // v1.12: RssFetcher.isElection stays as a utility;
        // nothing calls it now.
        // v1.15: the Video tab no longer filters RSS items with
        // isVideo — it lists YouTube videos fetched by their own
        // thread in refresh() (see youtubeSources). updateLists
        // only repaints whatever videoNews currently holds, so a
        // news refresh can never overwrite YouTube videos with
        // RSS video pages again.
        videoAdapter.setItems(new ArrayList<>(videoNews));
        // v1.42 (C6): the header must never claim "loading"
        // when no video thread is running. Before, any news
        // refresh reset a finished failure back to "Đang tải…"
        // and it sat there forever. Now: loading text only
        // while videoLoading is true and the list is still
        // empty; otherwise the plain title (a finished failure
        // lives in txtVideoNote, set by the video callback).
        if (txtVideoHead != null) txtVideoHead.setText(
                (videoLoading && videoNews.isEmpty()) ? "Đang tải video YouTube…" : "Video YouTube");
        startTitleTranslation();
        lastNewsStatus = status;
        if (tab != 0) applyTabStatus();
        if (bottomBlur != null) bottomBlur.postDelayed(new Runnable() { public void run() { bottomBlur.requestBlur(); } }, 150);
        if (topBlur != null) topBlur.postDelayed(new Runnable() { public void run() { topBlur.requestBlur(); } }, 150);
    }
    // v3.1: search wiring — header magnifier toggles the row;
    // typing filters (see applyNewsFilter); clear wipes the query;
    // searching hides the keyboard.
    private void setupSearch() {
        View btn = findViewById(R.id.btnSearch);
        if (btn != null) btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setSearchOpen(!searchOpen); }
        });
        final EditText ed = findViewById(R.id.edtSearch);
        if (ed != null) {
            ed.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                public void onTextChanged(CharSequence s, int st, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    searchQuery = s == null ? "" : s.toString();
                    applyNewsFilter();
                }
            });
            ed.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                    hideKeyboard();
                    return true;
                }
            });
        }
        View clear = findViewById(R.id.btnSearchClear);
        if (clear != null) clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                EditText ed2 = findViewById(R.id.edtSearch);
                if (ed2 != null) ed2.setText("");
            }
        });
    }
    private void setSearchOpen(boolean open) {
        searchOpen = open;
        View box = findViewById(R.id.searchBox);
        if (box != null) box.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            EditText ed = findViewById(R.id.edtSearch);
            if (ed != null) {
                ed.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(ed, InputMethodManager.SHOW_IMPLICIT);
            }
        } else {
            EditText ed = findViewById(R.id.edtSearch);
            if (ed != null) ed.setText("");
            searchQuery = "";
            applyNewsFilter();
            hideKeyboard();
        }
    }
    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            View f = getCurrentFocus();
            if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }
    // v3.1: saved-for-later — long-press a news/video card to
    // save, long-press here to remove. Video items keep their
    // channelId, so opening one goes back to the player.
    private void refreshSavedList() {
        if (savedAdapter == null) return;
        // Nightly: the Saved tab now has two modes — Đã lưu and
        // Lịch sử đã đọc. The toggle is styled like the lottery
        // region chips (selected = pill + bold).
        View btnSaved = findViewById(R.id.btnSavedTab);
        View btnHist = findViewById(R.id.btnHistoryTab);
        View btnClear = findViewById(R.id.btnClearHistory);
        if (btnSaved != null) styleSavedTab((TextView) btnSaved, savedMode == 0);
        if (btnHist != null) styleSavedTab((TextView) btnHist, savedMode == 1);
        List<NewsItem> items;
        String emptyText;
        if (savedMode == 1) {
            items = ReadHistory.loadHistory(this);
            emptyText = "Chưa đọc bài nào — mở một bài ở tab Tin mới, bài đó sẽ hiện ở đây.";
            if (btnClear != null) btnClear.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        } else {
            items = NewsStore.loadSaved(this);
            emptyText = "Chưa có tin đã lưu — nhấn giữ một tin ở tab Tin mới hoặc Video để lưu lại đọc sau.";
            if (btnClear != null) btnClear.setVisibility(View.GONE);
        }
        savedAdapter.setItems(items);
        View empty = findViewById(R.id.txtSavedEmpty);
        if (empty != null) {
            ((TextView) empty).setText(emptyText);
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (tab == 3) applyTabStatus();
    }

    private void styleSavedTab(TextView tv, boolean selected) {
        if (selected) { tv.setBackground(Theme.pillDrawable(this)); tv.setTextColor(Theme.pillText(this)); }
        else { tv.setBackgroundColor(0x00000000); tv.setTextColor(Theme.tabUnselected(this)); }
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    // Nightly: clear the whole reading history (with confirm —
    // it also undims the read articles in the news list).
    private void clearHistoryConfirm() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Xoá lịch sử đã đọc?")
                .setMessage("Toàn bộ lịch sử đã đọc sẽ bị xoá, và các bài đã đọc sẽ hiện lại bình thường (không mờ nữa).")
                .setPositiveButton("Xoá", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        ReadHistory.clearHistory(MainActivity.this);
                        refreshSavedList();
                        if (newsAdapter != null) newsAdapter.notifyDataSetChanged();
                        Toast.makeText(MainActivity.this, "Đã xoá lịch sử đã đọc", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Huỷ", null)
                .show();
    }
    private void saveItem(NewsItem it) {
        if (it == null) return;
        boolean now = NewsStore.toggleSaved(this, it);
        refreshSavedList();
        Toast.makeText(this, now ? "Đã lưu — xem ở tab Đã lưu" : "Đã bỏ lưu tin này", Toast.LENGTH_SHORT).show();
    }
    private void openSavedItem(NewsItem it) {
        if (it == null) return;
        if (it.channelId != null && !it.channelId.isEmpty()) openVideo(it);
        else openLink(it.link, it.title, it);
    }
    @Override public void onBackPressed() {
        if (searchOpen) { setSearchOpen(false); return; }
        super.onBackPressed();
    }
    // v3.0: chuyên mục tin tức. The chip row (catRow) lists
    // "Tất cả" + the 10 categories; tapping one filters
    // `all` by the item's category (see NewsItem.category).
    // "Tất cả" is the default and shows everything, including
    // uncategorized and video-feed items.
    private static final String[] CATEGORIES = {"Tất cả", "Chính trị", "Tin Mỹ", "Thế giới", "Kinh tế", "Công nghệ", "Thể thao", "Giải trí", "Sức khỏe", "Khoa học", "Ý kiến"};
    private String selectedCategory = "Tất cả";
    // ---- v3.3: lottery tab (Xổ số 3 miền) ----
    private void setupLottery() {
        // v3.5: chip order is Nam - Trung - Bắc (not the
        // region-constant order MB/MT/MN).
        int[] chipIds = {R.id.lotChipMN, R.id.lotChipMT, R.id.lotChipMB};
        final int[] chipRegions = {LotteryFetcher.MN, LotteryFetcher.MT, LotteryFetcher.MB};
        for (int i = 0; i < chipIds.length; i++) {
            final int region = chipRegions[i];
            View chip = findViewById(chipIds[i]);
            if (chip != null) chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (lotteryRegion == region) return;
                    lotteryRegion = region;
                    styleLotteryChips();
                    refreshLottery(false);
                }
            });
        }
        View prev = findViewById(R.id.btnLotPrev);
        if (prev != null) prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { shiftLotteryDate(-1); } });
        View next = findViewById(R.id.btnLotNext);
        if (next != null) next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { shiftLotteryDate(1); } });
        View cal = findViewById(R.id.btnLotCal);
        if (cal != null) cal.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickLotteryDate(); } });
        // v3.12: dò vé số — nhập số, đối chiếu với kết quả
        // đang hiện trên tab (đúng đài + đúng ngày).
        View btnCheck = findViewById(R.id.btnLotCheck);
        if (btnCheck != null) btnCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { checkLotteryNumbers(); }
        });
        // Nightly: thống kê 30 ngày (tần suất, lô gan, đầu-đuôi).
        View btnStats = findViewById(R.id.btnLotStats);
        if (btnStats != null) btnStats.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLotteryStats(); }
        });
        styleLotteryChips();
        updateLotteryDateLabel();
    }

    // Nightly: thống kê xổ số 30 ngày cho miền đang chọn —
    // số về nhiều nhất, số lâu chưa về (lô gan), đầu/đuôi về
    // nhiều nhất. The fetch runs off the UI thread; the dialog
    // shows a loading state until the numbers land.
    private void showLotteryStats() {
        final android.app.AlertDialog progress = new android.app.AlertDialog.Builder(this, Theme.dialogTheme(this))
                .setTitle("Thống kê 30 ngày")
                .setMessage("Đang tải kết quả 30 ngày của " + LotteryFetcher.REGION_NAMES[lotteryRegion] + "…")
                .setCancelable(false)
                .create();
        progress.show();
        Theme.styleDialog(progress, this);
        final int region = lotteryRegion;
        LotteryStats.compute(region, 30, new LotteryStats.Cb() {
            public void onDone(final LotteryStats.Result r) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { progress.dismiss(); } catch (Exception ignored) {}
                        if (r == null || r.daysCounted == 0) {
                            Toast.makeText(MainActivity.this, "Không tải được kết quả để thống kê — kiểm tra mạng rồi thử lại.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
                        sv.addView(buildLotteryStatsView(r));
                        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(MainActivity.this, Theme.dialogTheme(MainActivity.this))
                                .setTitle("Thống kê 30 ngày — " + LotteryFetcher.REGION_NAMES[region])
                                .setView(sv)
                                .setPositiveButton("Đóng", null)
                                .create();
                        dlg.show();
                        Theme.styleDialog(dlg, MainActivity.this);
                    }
                });
            }
        });
    }

    // Nightly: the stats dialog, rebuilt in the app's design
    // language — theme dialog (rounded window + pill buttons),
    // section headers in the header colour, one row per number
    // with a proportional bar (navy/white for frequency, red
    // for lô gan). Replaces the plain-text dialog, a stock
    // white AlertDialog that clashed with the app's glass look.
    private android.view.View buildLotteryStatsView(LotteryStats.Result r) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), (int) (16 * d));
        TextView sub = new TextView(this);
        sub.setText(r.daysCounted + " kỳ có kết quả trong 30 ngày gần nhất.");
        sub.setTextSize(13);
        sub.setTextColor(Theme.noteText(this));
        sub.setPadding(0, 0, 0, (int) (4 * d));
        lay.addView(sub);
        // Most frequent, top 10.
        List<Map.Entry<String, Integer>> freq = new ArrayList<>(r.freq.entrySet());
        Collections.sort(freq, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int dd = b.getValue() - a.getValue();
                return dd != 0 ? dd : a.getKey().compareTo(b.getKey());
            }
        });
        lay.addView(statSection("Về nhiều nhất"));
        int maxF = freq.isEmpty() ? 1 : freq.get(0).getValue();
        for (int i = 0; i < Math.min(10, freq.size()); i++)
            lay.addView(statRow(freq.get(i).getKey(), freq.get(i).getValue() + " lần",
                    (float) freq.get(i).getValue() / maxF, Theme.headerText(this)));
        // Lô gan, top 10.
        List<Map.Entry<String, Integer>> gan = new ArrayList<>(r.lastSeenDaysAgo.entrySet());
        Collections.sort(gan, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int dd = b.getValue() - a.getValue();
                return dd != 0 ? dd : a.getKey().compareTo(b.getKey());
            }
        });
        lay.addView(statSection("Lâu chưa về (lô gan)"));
        int maxG = gan.isEmpty() ? 1 : gan.get(0).getValue();
        for (int i = 0; i < Math.min(10, gan.size()); i++) {
            int dd = gan.get(i).getValue();
            String v = dd >= r.daysRequested ? "chưa về 30 ngày" : dd + " ngày";
            lay.addView(statRow(gan.get(i).getKey(), v, (float) dd / Math.max(maxG, 1), 0xFFDC2626));
        }
        // Head / tail, top 5 each.
        lay.addView(statSection("Đầu về nhiều nhất"));
        addTopDigits(lay, r.headFreq, 5);
        lay.addView(statSection("Đuôi về nhiều nhất"));
        addTopDigits(lay, r.tailFreq, 5);
        return lay;
    }

    private TextView statSection(String title) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(Theme.headerText(this));
        t.setPadding(0, (int) (16 * d), 0, (int) (4 * d));
        return t;
    }

    private void addTopDigits(LinearLayout lay, int[] counts, int top) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) idx.add(i);
        Collections.sort(idx, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return counts[b] - counts[a]; }
        });
        int max = idx.isEmpty() ? 1 : counts[idx.get(0)];
        for (int i = 0; i < Math.min(top, idx.size()); i++)
            lay.addView(statRow(String.valueOf(idx.get(i)), counts[idx.get(i)] + " lần",
                    (float) counts[idx.get(i)] / Math.max(max, 1), Theme.headerText(this)));
    }

    // One stat row: bold number | proportional bar | count.
    // The bar is a rounded track with a fill whose width is
    // the value's share of the section max.
    private LinearLayout statRow(String label, String value, float fraction, int barColor) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (5 * d), 0, (int) (5 * d));
        TextView num = new TextView(this);
        num.setText(label);
        num.setTextSize(16);
        num.setTypeface(null, Typeface.BOLD);
        num.setTextColor(Theme.bodyText(this));
        num.setMinWidth((int) (40 * d));
        row.addView(num);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(Theme.isLight(this) ? 0x140A1B4A : 0x33FFFFFF);
        trackBg.setCornerRadius(4 * d);
        track.setBackground(trackBg);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, (int) (8 * d), 1f);
        trackLp.setMargins((int) (10 * d), 0, (int) (10 * d), 0);
        View fill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(barColor);
        fillBg.setCornerRadius(4 * d);
        fill.setBackground(fillBg);
        float f = Math.max(0.05f, Math.min(1f, fraction));
        track.addView(fill, new LinearLayout.LayoutParams(0, (int) (8 * d), f));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, (int) (8 * d), Math.max(1f - f, 0.02f)));
        row.addView(track, trackLp);
        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(13);
        val.setTextColor(Theme.subText(this));
        val.setGravity(Gravity.END);
        val.setMinWidth((int) (52 * d));
        row.addView(val);
        return row;
    }

    private Calendar midnightToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }
    private void shiftLotteryDate(int delta) {
        if (lotteryDate == null) lotteryDate = midnightToday();
        lotteryDate.add(Calendar.DAY_OF_MONTH, delta);
        if (lotteryDate.after(midnightToday())) lotteryDate = midnightToday();
        updateLotteryDateLabel();
        refreshLottery(false);
    }
    private void pickLotteryDate() {
        if (lotteryDate == null) lotteryDate = midnightToday();
        android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(this, Theme.dialogTheme(this),
            new android.app.DatePickerDialog.OnDateSetListener() {
                public void onDateSet(DatePicker view, int y, int m, int d) {
                    Calendar c = Calendar.getInstance();
                    c.set(y, m, d, 0, 0, 0); c.set(Calendar.MILLISECOND, 0);
                    if (c.after(midnightToday())) c = midnightToday();
                    lotteryDate = c;
                    updateLotteryDateLabel();
                    refreshLottery(false);
                }
            },
            lotteryDate.get(Calendar.YEAR), lotteryDate.get(Calendar.MONTH), lotteryDate.get(Calendar.DAY_OF_MONTH));
        dlg.getDatePicker().setMaxDate(System.currentTimeMillis());
        dlg.show();
    }
    private String lotteryDateLabel(Calendar c) {
        String[] dow = {"", "Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy"};
        return dow[c.get(Calendar.DAY_OF_WEEK)] + ", "
                + c.get(Calendar.DAY_OF_MONTH) + "/" + (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.YEAR);
    }
    private void updateLotteryDateLabel() {
        TextView tv = findViewById(R.id.txtLotDate);
        if (tv == null || lotteryDate == null) return;
        boolean today = LotteryFetcher.sameDay(lotteryDate, Calendar.getInstance());
        tv.setText((today ? "Hôm nay • " : "") + lotteryDateLabel(lotteryDate));
        View next = findViewById(R.id.btnLotNext);
        if (next != null) { next.setEnabled(!today); next.setAlpha(today ? 0.3f : 1f); }
    }
    // v3.6: blinking live dot — the red dot drawable on the
    // chip of the region that is drawing right now, its alpha
    // breathing 255 <-> 40 (~1.4s per blink). The animators
    // are infinite, so they must be cancelled whenever the
    // chips are re-styled and when the activity pauses.
    private final List<android.animation.ObjectAnimator> liveDotAnims = new ArrayList<>();
    private void stopLiveDotAnims() {
        for (android.animation.ObjectAnimator a : liveDotAnims) a.cancel();
        liveDotAnims.clear();
    }
    private void startLiveDotBlink(android.graphics.drawable.Drawable d) {
        if (d == null) return;
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofInt(d, "alpha", 255, 40);
        a.setDuration(700);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.start();
        liveDotAnims.add(a);
    }
    private void styleLotteryChips() {
        int[] chipIds = {R.id.lotChipMN, R.id.lotChipMT, R.id.lotChipMB};
        int[] chipRegions = {LotteryFetcher.MN, LotteryFetcher.MT, LotteryFetcher.MB};
        stopLiveDotAnims();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < chipIds.length; i++) {
            TextView chip = findViewById(chipIds[i]);
            if (chip == null) continue;
            boolean sel = (chipRegions[i] == lotteryRegion);
            if (sel) { chip.setBackground(Theme.pillDrawable(this)); chip.setTextColor(Theme.pillText(this)); }
            else { chip.setBackgroundColor(0x00000000); chip.setTextColor(Theme.tabUnselected(this)); }
            chip.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            // v3.6: the live dot is a real drawable that blinks
            // (see dot_live.xml), not a text character.
            String name = LotteryFetcher.REGION_NAMES[chipRegions[i]];
            if (LotteryFetcher.isLiveNow(chipRegions[i], Calendar.getInstance())) {
                android.graphics.drawable.Drawable dot = getDrawable(R.drawable.dot_live);
                if (dot != null) {
                    dot = dot.mutate();
                    chip.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
                    chip.setCompoundDrawablePadding((int) (6 * d));
                    startLiveDotBlink(dot);
                }
            } else chip.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            chip.setText(name);
        }
    }
    // v3.31: key for one drawn number, and a collector over
    // a DayResult's draws. The date is part of the key so a
    // number from another day never highlights by accident.
    private static String liveNewKey(String dateKey, String station, String label, String number) {
        return dateKey + "|" + station + "|" + label + "|" + number;
    }
    private static void collectDrawKeys(List<LotteryFetcher.Draw> draws, Set<String> out) {
        if (draws == null) return;
        for (LotteryFetcher.Draw d : draws) {
            if (d.date == null) continue;
            String dk = LotteryFetcher.dateKey(d.date);
            for (LotteryFetcher.PrizeRow row : d.prizes) {
                for (String n : row.numbers) out.add(liveNewKey(dk, d.provinceCode, row.label, n));
            }
        }
    }
    // Live poll: re-fetch every 10s only while the lottery
    // tab is open, the date is today, and the time is from
    // 15 min before until ~50 min after that region's draw.
    private void scheduleLotteryPoll() {
        lotteryPollHandler.removeCallbacks(lotteryPollRunnable);
        if (tab != 2 || lotteryDate == null) return;
        if (!LotteryFetcher.sameDay(lotteryDate, Calendar.getInstance())) return;
        Calendar now = Calendar.getInstance();
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int start = LotteryFetcher.DRAW_START_MIN[lotteryRegion];
        if (mins < start - 15 || mins > start + 50) return;
        lotteryPollHandler.postDelayed(lotteryPollRunnable, 10000);
    }
    private void refreshLottery(final boolean force) {
        if (lotteryDate == null) lotteryDate = midnightToday();
        final int region = lotteryRegion;
        final Calendar date = (Calendar) lotteryDate.clone();
        final String key = region + "|" + LotteryFetcher.dateKey(date);
        if (!force) {
            LotteryFetcher.DayResult cached = lotteryCache.get(key);
            if (cached != null) { renderLottery(cached, false); scheduleLotteryPoll(); return; }
        }
        if (lotteryLoading) return;
        lotteryLoading = true;
        setLotteryStatus("Đang tải kết quả " + LotteryFetcher.REGION_NAMES[region] + "…");
        new Thread(new Runnable() {
            public void run() {
                final LotteryFetcher.DayResult res = LotteryFetcher.fetch(region, date);
                // v3.31: live per-number reveal — diff against
                // the previous cached result for this same
                // region+date; numbers present now but absent
                // before just landed. Only when the previous
                // result already held today's draws; a first
                // load, or a load replacing previous-draw
                // placeholders, marks nothing as new.
                final Set<String> arrived = new HashSet<>();
                LotteryFetcher.DayResult prev = lotteryCache.get(key);
                if (prev != null && !prev.draws.isEmpty() && !res.draws.isEmpty()) {
                    Set<String> before = new HashSet<>();
                    collectDrawKeys(prev.draws, before);
                    Set<String> nowKeys = new HashSet<>();
                    collectDrawKeys(res.draws, nowKeys);
                    for (String k : nowKeys) if (!before.contains(k)) arrived.add(k);
                }
                // v3.7: before today's draw, the tab shows
                // TODAY's stations with their latest previous
                // draw — computed here on the worker thread,
                // since it may fetch a few dated pages.
                if (!res.drawDone && res.error == null)
                    res.previousDraws = LotteryFetcher.todayPrevious(res, date);
                lotteryCache.put(key, res);
                runOnUiThread(new Runnable() {
                    public void run() {
                        lotteryLoading = false;
                        // The user may have switched region/date
                        // while this fetch was in flight — only
                        // paint when it is still the current one.
                        if (region == lotteryRegion && LotteryFetcher.sameDay(date, lotteryDate)) {
                            long nowMs = System.currentTimeMillis();
                            for (String k : arrived) liveNewAt.put(k, nowMs);
                            livePollNewCount = arrived.size();
                            renderLottery(res, true);
                        }
                        scheduleLotteryPoll();
                    }
                });
            }
        }).start();
    }
    private void setLotteryStatus(String s) {
        lotteryStatusLine = s;
        TextView tv = findViewById(R.id.txtLotStatus);
        if (tv != null) tv.setText(s);
        if (tab == 2) applyTabStatus();
    }
    private void renderLottery(LotteryFetcher.DayResult res, boolean fresh) {
        LinearLayout cards = findViewById(R.id.lotCards);
        if (cards == null) return;
        cards.removeAllViews();
        stopRolling(); // v3.32: the old card (and its rolling row) is gone.
        // v3.31: drop "just landed" highlights older than 90s.
        long nowMs = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = liveNewAt.entrySet().iterator();
        while (it.hasNext()) if (nowMs - it.next().getValue() > 90000) it.remove();
        float d = getResources().getDisplayMetrics().density;
        if (res.error != null && res.draws.isEmpty() && res.latestDraws.isEmpty()) {
            setLotteryStatus(res.error);
            TextView empty = new TextView(this);
            empty.setText(res.error);
            empty.setTextColor(Theme.noteText(this));
            empty.setTextSize(13);
            empty.setPadding((int) (4 * d), (int) (12 * d), (int) (4 * d), (int) (12 * d));
            cards.addView(empty);
            View ss = findViewById(R.id.lotStationScroll);
            if (ss != null) ss.setVisibility(View.GONE);
            return;
        }
        if (!res.drawDone) {
            int start = LotteryFetcher.DRAW_START_MIN[res.region];
            setLotteryStatus("Chưa tới giờ quay (" + (start / 60) + "h"
                    + String.format(Locale.US, "%02d", start % 60)
                    + ") — kết quả kỳ trước của các đài quay hôm nay.");
        } else if (fresh) {
            // v3.5: red live mark while this region is drawing.
            boolean live = LotteryFetcher.isLiveNow(res.region, Calendar.getInstance())
                    && LotteryFetcher.sameDay(res.date, Calendar.getInstance());
            // v3.31: when the latest poll brought new numbers,
            // say how many just landed (with seconds — this is
            // the real-time feed now).
            if (live && livePollNewCount > 0) {
                setLotteryStatus("🔴 Đang quay — vừa về " + livePollNewCount + " số mới • "
                        + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " • Nguồn: Minh Ngọc");
            } else {
                setLotteryStatus((live ? "🔴 Đang quay — đã cập nhật " : "Đã cập nhật ")
                        + new SimpleDateFormat("HH:mm", Locale.US).format(new Date()) + " • Nguồn: Minh Ngọc");
            }
        } else {
            boolean live = LotteryFetcher.isLiveNow(res.region, Calendar.getInstance())
                    && LotteryFetcher.sameDay(res.date, Calendar.getInstance());
            setLotteryStatus(live ? "🔴 Đang quay — đang tải kết quả mới…"
                    : "Kết quả ngày " + LotteryFetcher.fmt(res.date) + " • Nguồn: Minh Ngọc");
        }
        // v3.7: before the draw, show today's stations with
        // their previous draw (previousDraws); fall back to
        // the latest draws if no previous draw was found.
        List<LotteryFetcher.Draw> show = res.draws.isEmpty()
                ? (!res.previousDraws.isEmpty() ? res.previousDraws : res.latestDraws)
                : res.draws;
        boolean showingPrevious = res.draws.isEmpty() && (!res.previousDraws.isEmpty() || !res.latestDraws.isEmpty());
        currentLotteryDraws = new ArrayList<>(show);
        // v3.20: station sub-tabs (see renderLotteryStations) —
        // one tab per station; only the selected station's card
        // is shown. Dò vé still checks ALL stations of this
        // region+date (currentLotteryDraws above), not just the
        // visible one.
        renderLotteryStations(show, res.region, showingPrevious);
    }
    // v3.20: 3 miền chia 3 tab, mỗi miền có tab con là đài —
    // the region chips pick the miền; this row picks the đài.
    // One tab per station of the current region+date; tapping
    // a tab shows only that station's card. MB has one draw a
    // day, so the row hides and the single card shows as before.
    private void renderLotteryStations(final List<LotteryFetcher.Draw> show, final int region, final boolean showingPrevious) {
        LinearLayout cards = findViewById(R.id.lotCards);
        if (cards == null) return;
        cards.removeAllViews();
        stopRolling(); // v3.32: the card is rebuilt below.
        String key = region + "|" + LotteryFetcher.dateKey(lotteryDate == null ? Calendar.getInstance() : lotteryDate);
        if (lotteryStationCode == null || !key.equals(lotteryStationKey)) {
            lotteryStationKey = key;
            lotteryStationCode = show.isEmpty() ? null : stationCode(show.get(0), region);
        }
        View stationScroll = findViewById(R.id.lotStationScroll);
        LinearLayout row = findViewById(R.id.lotStationRow);
        if (show.size() <= 1 || row == null) {
            if (stationScroll != null) stationScroll.setVisibility(View.GONE);
        } else {
            if (stationScroll != null) stationScroll.setVisibility(View.VISIBLE);
            row.removeAllViews();
            float d = getResources().getDisplayMetrics().density;
            for (final LotteryFetcher.Draw draw : show) {
                final String code = stationCode(draw, region);
                TextView tab = new TextView(this);
                tab.setText(stationName(draw, region));
                tab.setTextSize(12);
                tab.setPadding((int) (12 * d), (int) (9 * d), (int) (12 * d), (int) (9 * d));
                boolean sel = code.equals(lotteryStationCode);
                if (sel) { tab.setBackground(Theme.pillDrawable(this)); tab.setTextColor(Theme.pillText(this)); }
                else { tab.setBackgroundColor(0x00000000); tab.setTextColor(Theme.tabUnselected(this)); }
                tab.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = (int) (8 * d);
                tab.setLayoutParams(lp);
                tab.setClickable(true);
                tab.setFocusable(true);
                tab.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (code.equals(lotteryStationCode)) return;
                        lotteryStationCode = code;
                        renderLotteryStations(show, region, showingPrevious);
                    }
                });
                row.addView(tab);
            }
        }
        // The selected station's card (first one if the stored
        // station is gone, e.g. after a region/date change).
        LotteryFetcher.Draw sel = null;
        for (LotteryFetcher.Draw draw : show) if (stationCode(draw, region).equals(lotteryStationCode)) { sel = draw; break; }
        if (sel == null && !show.isEmpty()) { sel = show.get(0); lotteryStationCode = stationCode(sel, region); }
        if (sel != null) cards.addView(buildLotteryCard(sel, region, showingPrevious));
        // v3.5: re-style the chips on every render so the red
        // live dot appears/disappears on time while the tab
        // stays open (the 10s poll re-renders).
        styleLotteryChips();
        maybeStartRolling(); // v3.32: the card may hold a rolling prize.
    }
    // MB blocks carry the draw code, not a province code — the
    // single station key is "MB"; MT/MN key by province code.
    private static String stationCode(LotteryFetcher.Draw draw, int region) {
        return region == LotteryFetcher.MB ? "MB" : draw.provinceCode;
    }
    private static String stationName(LotteryFetcher.Draw draw, int region) {
        if (region == LotteryFetcher.MB) return "Miền Bắc";
        return draw.province == null || draw.province.isEmpty() ? draw.provinceCode : draw.province;
    }
    // v3.32: prize rows are centred (numbers centred). v3.33:
    // the prize LABEL stays left (the user's correction) — a
    // fixed 76dp label column on the left, numbers centred in
    // the remaining width. Numbers stay monospace (v3.19) so
    // digits line up; the last-2-digits tail is bold red
    // (spannedNumbers) and Giải ĐB stays whole-number red.
    private LinearLayout buildPrizeRowView(String label, CharSequence numbers, boolean special) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(13);
        lab.setTextColor(Theme.noteText(this));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams((int) (76 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
        lab.setLayoutParams(llp);
        r.addView(lab);
        TextView nums = new TextView(this);
        nums.setText(numbers);
        nums.setTextSize(special ? 17 : 16);
        nums.setTypeface(Typeface.MONOSPACE, special ? Typeface.BOLD : Typeface.NORMAL);
        nums.setTextColor(special ? 0xFFDC2626 : Theme.bodyText(this));
        nums.setGravity(Gravity.CENTER);
        nums.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(nums);
        return r;
    }
    private View buildLotteryCard(LotteryFetcher.Draw draw, int region, boolean showingPrevious) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassUi.cardBg(this, 28f));
        card.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * d);
        card.setLayoutParams(lp);
        TextView head = new TextView(this);
        String title = region == LotteryFetcher.MB
                ? "Miền Bắc — " + (draw.province == null ? "" : draw.province)
                : (draw.province == null || draw.province.isEmpty() ? "—" : draw.province);
        head.setText(title);
        head.setTextSize(15);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(Theme.headerText(this));
        head.setGravity(Gravity.CENTER); // v3.32: centred layout.
        card.addView(head);
        if (showingPrevious) {
            TextView note = new TextView(this);
            // v3.7: the card shows WHICH draw it is, since it
            // is the station's previous draw, not today's.
            note.setText(draw.date != null ? "Kỳ quay " + lotteryDateLabel(draw.date)
                    : "Kết quả mới nhất (không phải ngày đã chọn)");
            note.setTextSize(11);
            note.setTextColor(Theme.noteText(this));
            note.setGravity(Gravity.CENTER); // v3.32: centred layout.
            card.addView(note);
        }
        // v3.32: live rolling — which prize is being drawn
        // right now (null when the draw is done or not live).
        boolean liveDraw = !showingPrevious
                && LotteryFetcher.isLiveNow(region, Calendar.getInstance())
                && draw.date != null && LotteryFetcher.sameDay(draw.date, Calendar.getInstance());
        LotteryFetcher.RollingPrize rp = liveDraw ? LotteryFetcher.rollingPrize(draw, region) : null;
        boolean rpPresent = false;
        if (rp != null) for (LotteryFetcher.PrizeRow row : draw.prizes)
            if (rp.label.equals(row.label)) { rpPresent = true; break; }
        int firstPrizeIdx = card.getChildCount();
        for (LotteryFetcher.PrizeRow row : draw.prizes) {
            boolean special = "Giải ĐB".equals(row.label);
            // v3.19: easy checking ("dễ dò") — numbers are
            // monospace so digits line up in columns, and the
            // last 2 digits of every number are bold red: that
            // tail is what you match a ticket against (Giải 8
            // IS the tail). The special prize stays whole-number
            // red bold, only bigger.
            CharSequence numsText = spannedNumbers(draw.date != null ? LotteryFetcher.dateKey(draw.date) : "",
                    draw.provinceCode, row.label, row.numbers, special);
            LinearLayout r = buildPrizeRowView(row.label, numsText, special);
            if (rp != null && rp.label.equals(row.label)) {
                rollingNumsView = (TextView) r.getChildAt(1);
                rollingDigits = rp.digits;
                rollingBase = (row.numbers == null || row.numbers.isEmpty()) ? "" : numsText;
                rollingNumsView.setText(rollingText(rollingBase, rollingDigits));
            }
            card.addView(r);
        }
        // v3.32: the prize being drawn is not on the page yet
        // (the source omits undrawn prizes) — add its rolling
        // row: at the end for MN/MT (ascending), before the
        // first prize row for MB (biggest-first).
        if (rp != null && !rpPresent) {
            boolean special = "Giải ĐB".equals(rp.label);
            LinearLayout rr = buildPrizeRowView(rp.label, "", special);
            rollingNumsView = (TextView) rr.getChildAt(1);
            rollingDigits = rp.digits;
            rollingBase = "";
            rollingNumsView.setText(rollingText("", rollingDigits));
            if (region == LotteryFetcher.MB) card.addView(rr, firstPrizeIdx);
            else card.addView(rr);
        }
        if (draw.dauDuoi != null && !draw.dauDuoi.isEmpty()) {
            TextView ddHead = new TextView(this);
            ddHead.setText("Đầu — Đuôi");
            ddHead.setTextSize(12);
            ddHead.setTypeface(null, Typeface.BOLD);
            ddHead.setTextColor(Theme.headerText(this));
            ddHead.setGravity(Gravity.CENTER); // v3.32: centred layout.
            ddHead.setPadding(0, (int) (8 * d), 0, (int) (2 * d));
            card.addView(ddHead);
            // v3.19: column headers — the 3 columns were
            // unexplained before (Chục = tens of numbers ending
            // in Số, Đ.Vị = units of numbers starting with Số).
            LinearLayout hr = new LinearLayout(this);
            hr.setOrientation(LinearLayout.HORIZONTAL);
            TextView ha = ddCell("Chục", 1f, false);
            TextView hb = ddCell("Số", 0f, true);
            TextView hc = ddCell("Đ.Vị", 1f, false);
            LinearLayout.LayoutParams hblp = new LinearLayout.LayoutParams((int) (28 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
            hb.setLayoutParams(hblp);
            hb.setGravity(android.view.Gravity.CENTER);
            ha.setTextColor(Theme.noteText(this));
            hb.setTextColor(Theme.noteText(this));
            hc.setTextColor(Theme.noteText(this));
            hr.addView(ha); hr.addView(hb); hr.addView(hc);
            card.addView(hr);
            for (String[] row : draw.dauDuoi) {
                LinearLayout r = new LinearLayout(this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                TextView a = ddCell(row[0], 1f, false);
                TextView b = ddCell(row[1], 0f, true);
                TextView c = ddCell(row[2], 1f, false);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams((int) (28 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
                b.setLayoutParams(blp);
                b.setGravity(android.view.Gravity.CENTER);
                r.addView(a); r.addView(b); r.addView(c);
                card.addView(r);
            }
        }
        return card;
    }
    private TextView ddCell(String text, float weight, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text == null || text.isEmpty() ? "—" : text);
        tv.setTextSize(12);
        tv.setTextColor(Theme.bodyText(this));
        tv.setGravity(Gravity.CENTER); // v3.32: centred layout.
        // v3.19: monospace, like the prize numbers — digits in
        // the head/tail lists line up for checking.
        tv.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        tv.setLayoutParams(lp);
        return tv;
    }
    // v3.19: prize numbers with the last 2 digits (the tail
    // you check a ticket against) in bold red. Numbers are
    // joined with 3 spaces, as before; the spans sit on the
    // tail of each number. The special prize is already
    // whole-number red bold (via the TextView colour).
    // v3.31: a number that just landed in a live poll gets a
    // yellow "just landed" highlight (dark gold in dark mode)
    // plus bold, for 90s — so each number is seen arriving.
    private CharSequence spannedNumbers(String dateKey, String station, String label, List<String> nums, boolean special) {
        if (nums == null || nums.isEmpty()) return "—";
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int hlBg = Theme.isLight(this) ? 0xFFFFEB3B : 0xFFB8860B;
        for (int i = 0; i < nums.size(); i++) {
            if (i > 0) sb.append("   ");
            String n = nums.get(i);
            int start = sb.length();
            sb.append(n);
            if (liveNewAt.containsKey(liveNewKey(dateKey, station, label, n))) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(hlBg), start, start + n.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, start + n.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!special && n.length() >= 2) {
                int tail = start + n.length() - 2;
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), tail, start + n.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFDC2626), tail, start + n.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }
    private void buildCatChips() {
        LinearLayout row = findViewById(R.id.catRow);
        if (row == null) return;
        row.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (final String cat : CATEGORIES) {
            TextView chip = new TextView(this);
            chip.setText(cat);
            chip.setTextSize(13);
            chip.setPadding((int) (12 * d), (int) (11 * d), (int) (12 * d), (int) (11 * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * d);
            chip.setLayoutParams(lp);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectedCategory = cat; refreshCatChips(); applyNewsFilter(); }
            });
            row.addView(chip);
        }
        refreshCatChips();
    }
    private void refreshCatChips() {
        LinearLayout row = findViewById(R.id.catRow);
        if (row == null) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView chip = (TextView) v;
            boolean sel = CATEGORIES[i].equals(selectedCategory);
            if (sel) {
                chip.setBackground(Theme.pillDrawable(this));
                chip.setTextColor(Theme.pillText(this));
            } else {
                chip.setBackgroundColor(0x00000000);
                chip.setTextColor(Theme.tabUnselected(this));
            }
            chip.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
        // v3.1: scroll the selected chip into view (centred
        // when possible) so a late-category pick never sits
        // off-screen with no sign of where it went.
        final HorizontalScrollView sc = findViewById(R.id.catScroll);
        if (sc != null) sc.post(new Runnable() {
            public void run() {
                for (int i = 0; i < row.getChildCount(); i++) {
                    if (CATEGORIES[i].equals(selectedCategory)) {
                        View chip = row.getChildAt(i);
                        int x = chip.getLeft() - (sc.getWidth() - chip.getWidth()) / 2;
                        if (x < 0) x = 0;
                        sc.smoothScrollTo(x, 0);
                        break;
                    }
                }
            }
        });
    }
    private void applyNewsFilter() {
        if (newsAdapter == null) return;
        // v3.2: the category rides on the item now (its feed's
        // category); the name map is only a fallback for cached
        // items saved before v3.2, which carry no category.
        java.util.Map<String, String> catByName = new java.util.HashMap<>();
        if (sources != null) for (Source s : sources) catByName.put(s.name, s.category);
        String q = norm(searchQuery);
        List<NewsItem> filtered = new ArrayList<>();
        for (NewsItem it : all) {
            if (selectedCategory != null && !"Tất cả".equals(selectedCategory)) {
                String cat = (it.category == null || it.category.isEmpty()) ? catByName.get(it.source) : it.category;
                if (!selectedCategory.equals(cat)) continue;
            }
            if (!q.isEmpty()) {
                String hay = norm(it.title) + " " + norm(it.desc == null ? "" : it.desc) + " " + norm(it.source);
                if (!hay.contains(q)) continue;
            }
            filtered.add(it);
        }
        newsAdapter.setItems(filtered);
        updateBreakingBanner();
        // v3.1: result count while searching.
        TextView cnt = findViewById(R.id.txtSearchCount);
        if (cnt != null) {
            if (q.isEmpty()) cnt.setVisibility(View.GONE);
            else { cnt.setText(filtered.size() + " kết quả"); cnt.setVisibility(View.VISIBLE); }
        }
    }
    // v3.35: Breaking News — only TRULY hot news. The first version
    // flagged anything containing "breaking", but the outlets use
    // that word loosely, so the banner fired on ordinary stories
    // (the user's 21:25 correction: "tin thật sự nóng"). Now a
    // story counts only when ALL three hold:
    //   1. its title LEADS with "BREAKING" — the outlets reserve a
    //      leading BREAKING: for urgent stories; a "breaking" buried
    //      in a description is usually incidental, and
    //   2. it is fresh — published within the last 60 minutes
    //      (a 2-hour-old "breaking" story is no longer breaking), and
    //   3. it comes from a major outlet (Fox, CNN, AP, NY Post, CBS,
    //      NBC) — not a partisan blog, sports or tech feed.
    // The newest such story is the banner; the banner is GONE when
    // nothing qualifies (which is most of the time, by design). It
    // is computed from `all`, not the selected category — a breaking
    // story is global. `all` is newest-first, so the first hit is
    // the newest.
    private NewsItem breakingItem;
    private android.animation.ObjectAnimator breakingDotAnim;
    private static boolean isBreaking(NewsItem it, long now) {
        if (it == null) return false;
        String src = it.source == null ? "" : it.source.toLowerCase(java.util.Locale.US);
        if (!src.equals("fox news") && !src.equals("cnn") && !src.equals("ap news")
                && !src.equals("new york post") && !src.equals("cbs news") && !src.equals("nbc news")) return false;
        if (it.time <= 0 || now - it.time > 60L * 60_000) return false;
        String t = it.title == null ? "" : it.title.trim().toLowerCase(java.util.Locale.US);
        return t.startsWith("breaking");
    }
    private void updateBreakingBanner() {
        NewsItem best = null; int count = 0;
        long now = System.currentTimeMillis();
        for (NewsItem it : all) {
            if (it == null || !isBreaking(it, now)) continue;
            count++;
            if (best == null) best = it;
        }
        // v3.35: a story the user dismissed stays hidden.
        String dismissed = getSharedPreferences("kenzelections", MODE_PRIVATE).getString("breaking_dismissed_key", "");
        if (best != null && best.key().equals(dismissed)) best = null;
        View bar = findViewById(R.id.breakingBar);
        if (bar == null) return;
        boolean changed = (breakingItem == null) != (best == null)
                || (best != null && breakingItem != null && !best.key().equals(breakingItem.key()));
        breakingItem = best;
        if (best == null) { bar.setVisibility(View.GONE); stopBreakingDotBlink(); return; }
        bar.setVisibility(View.VISIBLE);
        TextView tv = findViewById(R.id.txtBreakingTitle);
        String title = best.title;
        if (TranslateUtil.translateTitlesEnabled(this)) {
            String vi = TranslateUtil.titleVi(best.key());
            if (vi != null && !vi.isEmpty()) title = vi;
        }
        tv.setText(title);
        TextView cnt = findViewById(R.id.txtBreakingCount);
        if (count > 1) { cnt.setText("+" + (count - 1) + " tin nóng"); cnt.setVisibility(View.VISIBLE); }
        else cnt.setVisibility(View.GONE);
        if (changed) startBreakingDotBlink();
    }
    private void startBreakingDotBlink() {
        ImageView dot = findViewById(R.id.breakingDot);
        if (dot == null) return;
        if (breakingDotAnim != null) breakingDotAnim.cancel();
        breakingDotAnim = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.35f);
        breakingDotAnim.setDuration(700);
        breakingDotAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        breakingDotAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        breakingDotAnim.start();
    }
    private void stopBreakingDotBlink() {
        if (breakingDotAnim != null) { breakingDotAnim.cancel(); breakingDotAnim = null; }
        ImageView dot = findViewById(R.id.breakingDot);
        if (dot != null) dot.setAlpha(1f);
    }
    // v3.1: accent-insensitive search — lowercase + strip
    // diacritics (d with stroke -> d), so "kinh te" matches
    // "Kinh tế".
    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(java.util.Locale.US).replace('\u0111', 'd');
    }
// v1.39: re-blur the menu after content changes so it
    // never sits on a stale frame. Delayed like every other
    // blur refresh so the new frame has laid out first.
    private void requestMenuBlur() {
        if (bottomBlur != null) bottomBlur.postDelayed(new Runnable() { public void run() { bottomBlur.requestBlur(); } }, 150);
        if (topBlur != null) topBlur.postDelayed(new Runnable() { public void run() { topBlur.requestBlur(); } }, 150);
    }
    // v3.17: one call for both blur strips (header + menu), used
    // by every scroll listener — see the blurScroll note above.
    private void requestBothBlur() {
        if (bottomBlur != null) bottomBlur.requestBlur();
        if (topBlur != null) topBlur.requestBlur();
    }
    // v1.44: one video fallback phase over the channels whose
    // feed failed. mobile=true fetches m.youtube.com /videos
    // (compactVideoRenderer blocks); false fetches the desktop
    // /videos page (lockup blocks). skipUrls = channels an
    // earlier phase already fixed; okUrls collects the feed
    // URLs this phase fixed. Returns how many channels were
    // tried. Runs on the refresh worker thread: one thread per
    // failed channel, joined with the v1.40 35s bound, and each
    // channel paints progressively as soon as it lands (the
    // final callback still replaces the list with the full
    // deduped, sorted set, so an early paint can never leave
    // a stale or duplicated list behind).
    private int runVideoFallback(final List<Source> ys, final RssFetcher.Result r, final boolean mobile,
            final java.util.Set<String> skipUrls, final java.util.Set<String> okUrls, final List<NewsItem> out, final int[] okCount) {
        List<Thread> threads = new ArrayList<>();
        for (Source s : ys) {
            if (!s.enabled) continue;
            String st = r.status.get(s.url);
            if (st != null && st.startsWith("OK")) continue;
            if (skipUrls != null && skipUrls.contains(s.url)) continue;
            final String cid = channelIdFromFeedUrl(s.url);
            if (cid == null) continue;
            final String cname = s.name;
            final String surl = s.url;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        List<NewsItem> extra = mobile ? YoutubeChannelFetcher.fetchMobile(cid, cname) : YoutubeChannelFetcher.fetch(cid, cname);
                        if (extra != null && !extra.isEmpty()) {
                            out.addAll(extra);
                            synchronized (okCount) { okCount[0]++; }
                            if (okUrls != null) okUrls.add(surl);
                            final List<NewsItem> paint = extra;
                            main.post(new Runnable() {
                                public void run() {
                                    java.util.Set<String> keys = new java.util.HashSet<>();
                                    for (NewsItem old : videoNews) keys.add(old.key());
                                    boolean changed = false;
                                    for (NewsItem it : paint) {
                                        if (keys.add(it.key())) { videoNews.add(it); changed = true; }
                                    }
                                    if (changed) {
                                        videoAdapter.setItems(new ArrayList<>(videoNews));
                                        if (txtVideoHead != null) txtVideoHead.setText("Video YouTube");
                                        if (txtVideoNote != null) txtVideoNote.setVisibility(View.GONE);
                                        videoStatus = "Video: " + videoNews.size() + " video";
                                    }
                                }
                            });
                        }
                    } catch (Exception ignored) {}
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try { t.join(35000); } catch (InterruptedException ignored) {}
        }
        return threads.size();
    }
    private void refresh() {
        // v1.23: ignore a duplicate refresh while one is already
        // running (rapid Cập nhật taps, or a pull during a
        // refresh). A second refresh would start duplicate
        // news/video threads (2x network load) for the same data;
        // the running refresh's posts paint when they land.
        if (refreshing) { showStatusSticky("Đang cập nhật từ " + enabledCount() + " nguồn…"); return; }
        refreshing = true;
        final int myGen = ++refreshGen;
        refreshLeft = 2;
        main.postDelayed(new Runnable() { public void run() { if (refreshing && refreshGen == myGen) { refreshing = false; refreshLeft = 0; hidePullIcon(); } } }, 90000);
        showStatusSticky("Đang cập nhật từ " + enabledCount() + " nguồn…");
        // v1.24: the reload icon spins for the whole refresh
        // (pull or button tap — same "updating" signal).
        startPullSpin();
        // v1.25: LIVE check (see LiveChecker) runs on its own
        // thread, independent of the refresh guard — it
        // paints whenever it lands, and never blocks news /
        // video. Until it lands, the section shows
        // "checking" so the tab never looks broken.
        showLiveNote("Đang kiểm tra kênh trực tiếp…");
        new Thread(new Runnable() {
            public void run() {
                final List<LiveChecker.LiveChannel> live = LiveChecker.checkAll();
                main.post(new Runnable() {
                    public void run() { updateLiveSection(live); }
                });
            }
        }).start();
        final List<Source> snapshot = new ArrayList<>(sources);
        sourcesSig = calcSourcesSig();
        // v1.11 (suggestion 3): news runs in its own thread
        // so a slow video fetch never keeps fresh news waiting.
        // The news post runs on the main thread, so the field
        // writes below cannot interleave.
        new Thread(new Runnable() {
            public void run() {
                final RssFetcher.Result r = RssFetcher.fetch(snapshot);
                main.post(new Runnable() {
                    public void run() {
                        if (!r.items.isEmpty()) {
                            all = r.items;
                            NewsStore.saveCache(MainActivity.this, all);
                            // v3.12: the home-screen widget reads
                            // the same cache — repaint it.
                            NewsWidgetProvider.updateAll(MainActivity.this);
                            Set<String> seen = NewsStore.seen(MainActivity.this);
                            Set<String> allKeys = new HashSet<>(seen);
                            for (NewsItem it : all) allKeys.add(it.key());
                            NewsStore.saveSeen(MainActivity.this, allKeys);
                        }
                        String when = new SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
                        updateLists(r.items.isEmpty() ? "Không tải được tin mới — kiểm tra mạng, hoặc xem trạng thái từng nguồn trong Cài đặt → Nguồn tin." : "Đã cập nhật: " + all.size() + " tin từ " + enabledCount() + " nguồn • " + when);
                        // v3.1: one-time pull-to-refresh hint — the
                        // custom pull gesture has no visible affordance.
                        if (!r.items.isEmpty() && !getSharedPreferences("kenzelections", MODE_PRIVATE).getBoolean("pull_hint_shown", false)) {
                            getSharedPreferences("kenzelections", MODE_PRIVATE).edit().putBoolean("pull_hint_shown", true).apply();
                            Toast.makeText(MainActivity.this, "Mẹo: kéo danh sách xuống để cập nhật tin mới", Toast.LENGTH_LONG).show();
                        }
                        onRefreshPartDone(myGen);
                    }
                });
            }
        }).start();
        // v1.15: YouTube videos run in their own parallel thread
        // like results, so a slow YouTube feed never blocks news.
        // v1.37: say so in the header while it runs — otherwise
        // the previous run's error stays on screen during a
        // long fallback fetch and looks like a fresh failure.
        // v1.42: mark the video load running (C6 flag) and
        // clear any previous failure note — a new run owns
        // the header + note until its own callback lands.
        videoLoading = true;
        videoStatus = "Video: đang tải…";
        if (txtVideoNote != null) txtVideoNote.setVisibility(View.GONE);
        if (txtVideoHead != null) txtVideoHead.setText("Đang tải video…");
        new Thread(new Runnable() {
            public void run() {
                final List<Source> ys = youtubeSources();
                // v1.40: parallel feed fetch (see fetchParallel)
                // — the sequential fetch could stall the tab for
                // minutes on a slow connection.
                final RssFetcher.Result r = RssFetcher.fetchParallel(ys);
                // v1.36 fallback, v1.44 order: a channel whose
                // feed failed or came back empty is retried
                // from its mobile /videos page first (the
                // phone's 14:40 test proved www.youtube.com
                // dead on his connection while m.youtube.com
                // answers), then from its desktop /videos page
                // for channels mobile misses. Disabled sources
                // are never retried.
                // v1.37: the fallback fetches run in parallel
                // (one thread per failed channel, joined before
                // painting) — sequentially they could take
                // minutes on a slow connection, which looked
                // exactly like "still broken" while the tab kept
                // showing the previous run's error. Counts feed
                // the error text below so a screenshot shows
                // which stage failed.
                int feedOk = 0, feedTotal = 0;
                for (Source s : ys) {
                    if (!s.enabled) continue;
                    feedTotal++;
                    String st = r.status.get(s.url);
                    if (st != null && st.startsWith("OK")) feedOk++;
                }
                final int fFeedOk = feedOk, fFeedTotal = feedTotal;
                // v1.44: mobile-first fallback (see
                // YoutubeChannelFetcher). The phone's 14:40
                // test proved both www routes dead while
                // m.youtube.com answers, so the mobile /videos
                // phase runs first; desktop stays as last
                // resort for channels mobile misses. Counts
                // feed the error text below so a screenshot
                // shows which stage failed.
                final List<NewsItem> mobItems = Collections.synchronizedList(new ArrayList<NewsItem>());
                final int[] mobOk = new int[1];
                final java.util.Set<String> mobOkUrls = Collections.synchronizedSet(new java.util.HashSet<String>());
                final int fMobTried = runVideoFallback(ys, r, true, null, mobOkUrls, mobItems, mobOk);
                final int fMobOk = mobOk[0];
                r.items.addAll(mobItems);
                final List<NewsItem> fbItems = Collections.synchronizedList(new ArrayList<NewsItem>());
                final int[] fbOk = new int[1];
                final int fHtmlTried = runVideoFallback(ys, r, false, mobOkUrls, null, fbItems, fbOk);
                final int fHtmlOk = fbOk[0];
                r.items.addAll(fbItems);
                Map<String,NewsItem> seen = new LinkedHashMap<>();
                for (NewsItem it : r.items) if (!seen.containsKey(it.key())) seen.put(it.key(), it);
                r.items = new ArrayList<>(seen.values());
                Collections.sort(r.items, new Comparator<NewsItem>() {
                    public int compare(NewsItem a, NewsItem b) { return Long.compare(b.time, a.time); }
                });
                // v1.47: stamp the channel id on every video
                // (feed items never carried it; fallback items
                // already do) so the player can fetch related
                // videos from the same channel without a key.
                java.util.Map<String,String> idByName = new java.util.HashMap<>();
                for (Source s : ys) {
                    String cid = channelIdFromFeedUrl(s.url);
                    if (cid != null) idByName.put(s.name, cid);
                }
                for (NewsItem it : r.items) {
                    if ((it.channelId == null || it.channelId.isEmpty()) && idByName.containsKey(it.source)) it.channelId = idByName.get(it.source);
                }
                main.post(new Runnable() {
                    public void run() {
                        // v1.35 BUG: a failed refresh used to wipe
                        // the list (videoNews = r.items, empty on
                        // failure) — the user's 2026-11-04 10:35
                        // screenshot showed exactly that: 75
                        // videos at 10:13, then an empty list +
                        // error after YouTube stopped answering
                        // all 9 channel feeds at once (news still
                        // loaded, LIVE still worked — those do
                        // not use the feeds endpoint). Now a
                        // failed refresh keeps the previous
                        // videos (the civicAPI stale pattern)
                        // and only says so in a toast; the error
                        // text shows only when there are
                        // genuinely no videos at all.
                        // v1.42 (C6/T7): the header is always
                        // the plain title now; a finished failure
                        // lives in txtVideoNote (with the feed /
                        // channel counts), never as a header that
                        // a later news refresh can overwrite
                        // back into "loading".
                        videoLoading = false;
                        if (r.items.isEmpty() && !videoNews.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Không tải được video mới — đang hiện video cũ.", Toast.LENGTH_SHORT).show();
                            if (txtVideoHead != null) txtVideoHead.setText("Video YouTube");
                            if (txtVideoNote != null) txtVideoNote.setVisibility(View.GONE);
                            videoStatus = "Video: " + videoNews.size() + " video (cũ)";
                        } else {
                            videoNews = r.items;
                            videoAdapter.setItems(new ArrayList<>(videoNews));
                            if (txtVideoHead != null) txtVideoHead.setText("Video YouTube");
                            if (videoNews.isEmpty()) {
                                if (txtVideoNote != null) {
                                    txtVideoNote.setText("Chưa tải được video YouTube — kiểm tra mạng và thử Cập nhật lại. (feed: " + fFeedOk + "/" + fFeedTotal + ", di động: " + fMobOk + "/" + fMobTried + ", trang kênh: " + fHtmlOk + "/" + fHtmlTried + ")");
                                    txtVideoNote.setVisibility(View.VISIBLE);
                                }
                                videoStatus = "Video: chưa tải được (feed: " + fFeedOk + "/" + fFeedTotal + ", di động: " + fMobOk + "/" + fMobTried + ", trang kênh: " + fHtmlOk + "/" + fHtmlTried + ")";
                            } else {
                                if (txtVideoNote != null) txtVideoNote.setVisibility(View.GONE);
                                videoStatus = "Video: " + videoNews.size() + " video";
                            }
                        }
                        startTitleTranslation();
                        if (bottomBlur != null) bottomBlur.postDelayed(new Runnable() { public void run() { bottomBlur.requestBlur(); } }, 150);
                        if (topBlur != null) topBlur.postDelayed(new Runnable() { public void run() { topBlur.requestBlur(); } }, 150);
                        onRefreshPartDone(myGen);
                    }
                });
            }
        }).start();
    }
    // v1.25: paint the Video tab's LIVE section from a
    // LiveChecker result. Runs on the main thread; the live
    // post does not touch refreshLeft — the section updates
    // whenever its check lands, independent of the 3-part
    // refresh guard. A failed check is never shown as "not
    // live": if every check failed the note says the check
    // itself failed; if at least one check ran and none is
    // live, the note says so. Tapping a live row opens the
    // stream in the app's own player — exactly like a video
    // row tap since v1.45.
    private void showLiveNote(String text) {
        if (liveContainer == null) return;
        expandedLiveId = null;
        if (liveStickerRow != null) liveStickerRow.removeAllViews();
        liveContainer.removeAllViews();
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextColor(Theme.noteText(this));
        note.setTextSize(13);
        float d = getResources().getDisplayMetrics().density;
        note.setPadding((int) (12 * d), (int) (4 * d), (int) (12 * d), (int) (12 * d));
        liveContainer.addView(note);
    }
    // v1.26: LIVE section, compact sticker model (replaces the
    // v1.25 full cards). The v1.25 cards stacked up to 3 tall
    // rows above the video list and pushed the videos off the
    // screen — the user's screenshot proved it. Now the section
    // is one short horizontal sticker row (red LIVE pill +
    // channel name, scrollable sideways), and tapping a sticker
    // expands exactly one detail card below the row; tapping the
    // same sticker again, or scrolling the video list down,
    // collapses it, so the expanded card can never cover the
    // videos. The detail card's tap still opens the stream with
    // opens the in-app player (LivePlayerActivity), whose own
    // "Mở trong app YouTube" button is the fallback. A failed
    // check is never shown as "not live" (see LiveChecker).
    private void updateLiveSection(List<LiveChecker.LiveChannel> channels) {
        if (liveContainer == null) return;
        liveChannels = channels;
        expandedLiveId = null;
        if (liveStickerRow != null) liveStickerRow.removeAllViews();
        liveContainer.removeAllViews();
        boolean anyLive = false, anyOk = false;
        if (channels != null) for (LiveChecker.LiveChannel lc : channels) {
            if (lc.videoId != null && !lc.videoId.isEmpty()) anyLive = true;
            if (lc.error == null || lc.error.isEmpty()) anyOk = true;
        }
        if (!anyLive) {
            showLiveNote(!anyOk ? "Không kiểm tra được trạng thái trực tiếp — bấm Cập nhật để thử lại."
                    : "Hiện không có kênh nào đang trực tiếp.");
            return;
        }
        for (LiveChecker.LiveChannel lc : channels) {
            if (lc.videoId == null || lc.videoId.isEmpty()) continue;
            liveStickerRow.addView(buildLiveSticker(lc));
        }
    }
    private View buildLiveSticker(final LiveChecker.LiveChannel lc) {
        View chip = LayoutInflater.from(this).inflate(R.layout.item_live_sticker, liveStickerRow, false);
        chip.setTag(lc.videoId);
        TextView name = chip.findViewById(R.id.txtStickerChannel);
        name.setText(lc.name);
        styleLiveSticker(chip, false);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (8 * d);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (lc.videoId != null && lc.videoId.equals(expandedLiveId)) collapseLive();
                else expandLive(lc);
            }
        });
        return chip;
    }
    private void styleLiveSticker(View chip, boolean selected) {
        float d = getResources().getDisplayMetrics().density;
        TextView badge = chip.findViewById(R.id.txtStickerLive);
        TextView name = chip.findViewById(R.id.txtStickerChannel);
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(6f * d);
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(22f * d);
            bg.setColor(0xFFDC2626);
            chip.setBackground(bg);
            pill.setColor(0xFF991B1B);
            name.setTextColor(0xFFFFFFFF);
        } else {
            chip.setBackground(GlassUi.cardBg(this, 22f));
            pill.setColor(0xFFDC2626);
            name.setTextColor(Theme.bodyText(this));
        }
        badge.setBackground(pill);
    }
    private void restyleLiveStickers() {
        if (liveStickerRow == null) return;
        for (int i = 0; i < liveStickerRow.getChildCount(); i++) {
            View chip = liveStickerRow.getChildAt(i);
            Object tag = chip.getTag();
            styleLiveSticker(chip, tag != null && tag.equals(expandedLiveId));
        }
    }
    private void expandLive(LiveChecker.LiveChannel lc) {
        if (liveContainer == null) return;
        expandedLiveId = lc.videoId;
        liveContainer.removeAllViews();
        final String vid = lc.videoId;
        final String chName = lc.name;
        final String ttl = (lc.title != null && !lc.title.isEmpty()) ? lc.title : "Đang trực tiếp";
        View row = LayoutInflater.from(this).inflate(R.layout.item_live, liveContainer, false);
        row.setBackground(GlassUi.cardBg(this, 28f));
        TextView badge = row.findViewById(R.id.txtLiveBadge);
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(6f * d);
        bg.setColor(0xFFDC2626);
        badge.setBackground(bg);
        TextView ch = row.findViewById(R.id.txtLiveChannel);
        ch.setText(lc.name);
        ch.setTextColor(Theme.bodyText(this));
        TextView ti = row.findViewById(R.id.txtLiveTitle);
        ti.setText(lc.title != null && !lc.title.isEmpty() ? lc.title : "Đang trực tiếp");
        ti.setTextColor(Theme.subText(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * d);
        row.setLayoutParams(lp);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, LivePlayerActivity.class);
                it.putExtra("videoId", vid);
                it.putExtra("channel", chName);
                it.putExtra("title", ttl);
                startActivity(it);
            }
        });
        liveContainer.addView(row);
        restyleLiveStickers();
    }
    private void collapseLive() {
        expandedLiveId = null;
        if (liveContainer != null) liveContainer.removeAllViews();
        restyleLiveStickers();
    }
    // v1.24: one shared % formatter — 1 decimal, integers stay
    // integers. Replaces the raw-String.valueOf(double) sites that
    // printed 0.04666666666666666% and 59.81642857142857%.
    private static String fmt(double d) {
        return d == (int) d ? String.valueOf((int) d) : String.format(java.util.Locale.US, "%.1f", d);
    }
    // v1.42 (T2): civicAPI's last_updated is UTC, but the old
    // note printed it verbatim with no zone — a Vietnamese
    // reader took "06:14" as local time when it was 13:14.
    // Parse as UTC (or with its own offset when present) and
    // print local dd/MM HH:mm like every other time in the
    // app. Unparseable input falls back to the old verbatim
    // form rather than vanishing.
    private String apiTimeToLocal(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm"};
        for (String p : patterns) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(p, Locale.getDefault());
                if (p.endsWith("'Z'") || !p.contains("XXX")) in.setTimeZone(TimeZone.getTimeZone("UTC"));
                java.util.Date d = in.parse(raw);
                if (d != null) return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(d);
            } catch (Exception ignored) {}
        }
        return raw.length() >= 16 ? raw.substring(0, 16).replace("T", " ") : raw;
    }
    // v1.23: one of the three parallel refresh posts landed.
    // Runs on the main thread (all posts do), so no synchronisation
    // is needed. A stale post (from a refresh the 90s safety valve
    // already cleared) must not touch the new refresh's counter.
    private void onRefreshPartDone(int gen) {
        if (gen != refreshGen) return;
        if (refreshLeft > 0) refreshLeft--;
        if (refreshLeft <= 0) { refreshing = false; hidePullIcon(); }
    }
    private int enabledCount() {
        int n = 0; for (Source s : sources) if (s.enabled) n++; return n;
    }
    // v3.12: signature of the enabled source set (see onResume).
    private String calcSourcesSig() {
        StringBuilder sb = new StringBuilder();
        if (sources != null) for (Source s : sources) sb.append(s.name).append(s.enabled ? "1" : "0").append(";");
        return sb.toString();
    }
    // v3.12: dò vé số. One number wins a prize when one is a
    // suffix of the other — exactly how Vietnamese lottery
    // matching works (a 2-digit pick wins Giải 8 when it equals
    // the drawn number's last 2 digits; a full-length pick must
    // match the whole drawn number). Numbers are kept as
    // strings so leading zeros survive.
    private void checkLotteryNumbers() {
        hideKeyboard();
        EditText ed = findViewById(R.id.edtLotCheck);
        if (ed == null) return;
        List<String> picks = new ArrayList<>();
        for (String part : ed.getText().toString().split("[,\\s]+")) {
            String d = part.replaceAll("[^0-9]", "");
            if (!d.isEmpty() && !picks.contains(d)) picks.add(d);
            if (picks.size() >= 20) break;
        }
        if (picks.isEmpty()) { Toast.makeText(this, "Nhập số cần dò trước đã.", Toast.LENGTH_SHORT).show(); return; }
        if (currentLotteryDraws == null || currentLotteryDraws.isEmpty()) { Toast.makeText(this, "Chưa có kết quả để dò — đợi kết quả tải xong.", Toast.LENGTH_SHORT).show(); return; }
        StringBuilder hits = new StringBuilder();
        StringBuilder miss = new StringBuilder();
        for (String p : picks) {
            List<String> won = new ArrayList<>();
            for (LotteryFetcher.Draw draw : currentLotteryDraws) {
                String prov = (draw.province == null || draw.province.isEmpty())
                        ? LotteryFetcher.REGION_NAMES[lotteryRegion] : draw.province;
                for (LotteryFetcher.PrizeRow row : draw.prizes) {
                    for (String num : row.numbers) {
                        if (num.endsWith(p) || p.endsWith(num)) { won.add(row.label + " • " + prov + " (" + num + ")"); break; }
                    }
                }
            }
            if (won.isEmpty()) { if (miss.length() > 0) miss.append(", "); miss.append(p); }
            else {
                hits.append("Số ").append(p).append(" — trúng:\n");
                for (String w : won) hits.append("  • ").append(w).append("\n");
            }
        }
        String msg = hits.length() > 0 ? hits.toString() : "";
        if (miss.length() > 0) msg += (msg.isEmpty() ? "" : "\n") + "Không trúng: " + miss.toString() + ".";
        String scope = "Đang dò với kết quả " + LotteryFetcher.REGION_NAMES[lotteryRegion] + " ngày " + LotteryFetcher.fmt(lotteryDate) + ".";
        AlertDialog _dlg = new AlertDialog.Builder(this, Theme.dialogTheme(this))
                .setTitle("Kết quả dò vé")
                .setMessage(msg + "\n\n" + scope)
                .setPositiveButton("Đóng", null)
                .show();
        Theme.styleDialog(_dlg, this);
    }
}
