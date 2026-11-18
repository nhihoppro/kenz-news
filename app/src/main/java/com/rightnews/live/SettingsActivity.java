package com.rightnews.live;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
/** v1.5 settings screen, opened by the gear button in the header.
 *  Glass mode moved here from the header to keep the header compact. */
public class SettingsActivity extends Activity {
    private TextView txtTitles, txtGlass, txtNotify, txtTheme, txtBgPlay, txtPip, txtPerms, txtSourcesState, txtAutoplayState, txtViewModeState, txtFontSizeState, txtLotteryNotify, txtLotteryRegions;
    private TextView txtKeywordsState, txtAudioOnlyState, txtBackupState, txtRestoreState;
    private TextView txtWidgetAlphaState, txtWidgetCountState, txtWidgetThemeState;
    private android.widget.LinearLayout sourcesListBox;
    private TextView txtSourcesHint, txtSourcesNote;
    private java.util.List<Source> sources;
    private SourceAdapter sourceAdapter;
    private boolean sourcesExpanded = true;
    private String pendingBackupJson = "";
    // v1.14: the settings bar's BlurView (same construction as the
    // main header/menu bars).
    private BlurView settingsBlur;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Theme.preferHighRefreshRate(this);
        final View root = findViewById(R.id.settingsRoot);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
                return insets;
            }
        });
        // v1.14: no applyBar — the bar's BlurView draws blur +
        // tint + stroke, exactly like the main header/menu bars.
        settingsBlur = findViewById(R.id.settingsBlur);
        final View settingsScroll = findViewById(R.id.settingsScroll);
        if (settingsBlur != null && settingsScroll != null) {
            settingsBlur.setSource(settingsScroll);
            settingsScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                public void onScrollChange(View v, int x, int y, int ox, int oy) {
                    if (settingsBlur != null) settingsBlur.requestBlur();
                }
            });
        }
        // v1.11: About version comes from the APK itself, so it
        // can never go stale again (the layout still said 1.7 in
        // v1.10). Layout text stays as the fallback on failure.
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView about = findViewById(R.id.txtAboutTitle);
            if (about != null && vn != null) about.setText("Kenz News — phiên bản " + vn);
        } catch (Exception ignored) {}
        txtTitles = findViewById(R.id.txtTitlesState);
        txtGlass = findViewById(R.id.txtGlassState);
        txtNotify = findViewById(R.id.txtNotifyState);
        txtTheme = findViewById(R.id.txtThemeState);
        txtBgPlay = findViewById(R.id.txtBgPlayState);
        txtPip = findViewById(R.id.txtPipState);
        txtPerms = findViewById(R.id.txtPermsState);
        txtSourcesState = findViewById(R.id.txtSourcesState);
        txtAutoplayState = findViewById(R.id.txtAutoplayState);
        txtViewModeState = findViewById(R.id.txtViewModeState);
        txtFontSizeState = findViewById(R.id.txtFontSizeState);
        txtLotteryNotify = findViewById(R.id.txtLotteryNotifyState);
        txtLotteryRegions = findViewById(R.id.txtLotteryRegionsState);
        txtKeywordsState = findViewById(R.id.txtKeywordsState);
        txtAudioOnlyState = findViewById(R.id.txtAudioOnlyState);
        txtBackupState = findViewById(R.id.txtBackupState);
        txtRestoreState = findViewById(R.id.txtRestoreState);
        txtWidgetAlphaState = findViewById(R.id.txtWidgetAlphaState);
        txtWidgetCountState = findViewById(R.id.txtWidgetCountState);
        txtWidgetThemeState = findViewById(R.id.txtWidgetThemeState);
        sourcesListBox = findViewById(R.id.sourcesListBox);
        txtSourcesHint = findViewById(R.id.txtSourcesHint);
        txtSourcesNote = findViewById(R.id.txtSourcesNote);
        sources = Sources.load(this);
        sourcesExpanded = getSharedPreferences("kenzelections", MODE_PRIVATE).getBoolean("sources_expanded", true);
        // v3.1: compact view toggle — big card vs compact row.
        findViewById(R.id.rowViewMode).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = !Theme.compactView(SettingsActivity.this);
                Theme.setCompactView(SettingsActivity.this, now);
                refresh();
                Toast.makeText(SettingsActivity.this, now ? "Đã chuyển sang dạng Gọn" : "Đã chuyển sang dạng Thẻ lớn", Toast.LENGTH_SHORT).show();
            }
        });
        // v3.1: font size cycles Nhỏ → Vừa → Lớn.
        findViewById(R.id.rowFontSize).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int m = (Theme.fontMode(SettingsActivity.this) + 1) % 3;
                Theme.setFontMode(SettingsActivity.this, m);
                refresh();
                Toast.makeText(SettingsActivity.this, "Cỡ chữ: " + Theme.fontModeName(SettingsActivity.this), Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowAutoplay).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = !LivePlayerActivity.autoplayOn(SettingsActivity.this);
                LivePlayerActivity.setAutoplayOn(SettingsActivity.this, now);
                refresh();
                Toast.makeText(SettingsActivity.this, now ? "Đã bật tự phát" : "Đã tắt tự phát", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowTheme).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // v3.13: cycles Tối -> Sáng -> Theo hệ thống.
                Theme.cycleTheme(SettingsActivity.this);
                applyTheme();
                refresh();
                // v3.13: the widget follows the app theme.
                NewsWidgetProvider.updateAll(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this, "Giao diện: " + Theme.themeModeName(SettingsActivity.this), Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btnSettingsBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.rowTitles).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = !TranslateUtil.translateTitlesEnabled(SettingsActivity.this);
                TranslateUtil.setTranslateTitlesEnabled(SettingsActivity.this, now);
                refresh();
                Toast.makeText(SettingsActivity.this, now ? "Đã bật dịch tiêu đề" : "Đã tắt dịch tiêu đề", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowGlass).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                GlassUi.cycle(SettingsActivity.this);
                if (settingsBlur != null) settingsBlur.requestBlur();
                refresh();
            }
        });
        findViewById(R.id.rowNotify).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleNotify(); }
        });
        // v3.8: lottery draw-start notifications — master toggle
        // + which regions to hear about (multi-choice dialog).
        findViewById(R.id.rowLotteryNotify).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleLotteryNotify(); }
        });
        findViewById(R.id.rowLotteryRegions).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLotteryRegionsDialog(); }
        });
        findViewById(R.id.rowKeywords).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showKeywordsDialog(); }
        });
        findViewById(R.id.rowAudioOnly).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleAudioOnly(); }
        });
        findViewById(R.id.rowBackup).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doBackup(); }
        });
        findViewById(R.id.rowRestore).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doRestore(); }
        });
        // v3.14: widget customization — each row cycles one
        // setting and repaints the widget at once.
        findViewById(R.id.rowWidgetAlpha).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int next = (NewsWidgetProvider.widgetAlphaIdx(SettingsActivity.this) + 1) % 3;
                NewsWidgetProvider.setWidgetAlphaIdx(SettingsActivity.this, next);
                NewsWidgetProvider.updateAll(SettingsActivity.this);
                refresh();
                Toast.makeText(SettingsActivity.this, "Widget: " + widgetAlphaName(SettingsActivity.this), Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowWidgetCount).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = NewsWidgetProvider.widgetCount(SettingsActivity.this);
                int next = cur == 2 ? 4 : cur == 4 ? 6 : 2;
                NewsWidgetProvider.setWidgetCount(SettingsActivity.this, next);
                NewsWidgetProvider.updateAll(SettingsActivity.this);
                refresh();
                Toast.makeText(SettingsActivity.this, "Widget: " + next + " tin", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowWidgetTheme).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int next = (NewsWidgetProvider.widgetThemeMode(SettingsActivity.this) + 1) % 3;
                NewsWidgetProvider.setWidgetThemeMode(SettingsActivity.this, next);
                NewsWidgetProvider.updateAll(SettingsActivity.this);
                refresh();
                Toast.makeText(SettingsActivity.this, "Widget: " + widgetThemeName(SettingsActivity.this), Toast.LENGTH_SHORT).show();
            }
        });
        // v3.12: Sources live here now (the tab is gone) —
        // tapping the row collapses/expands the list below.
        findViewById(R.id.rowSources).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sourcesExpanded = !sourcesExpanded;
                getSharedPreferences("kenzelections", MODE_PRIVATE).edit().putBoolean("sources_expanded", sourcesExpanded).apply();
                refresh();
            }
        });
        findViewById(R.id.rowBgPlay).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = !LivePlayerActivity.bgPlayOn(SettingsActivity.this);
                LivePlayerActivity.setBgPlayOn(SettingsActivity.this, now);
                refresh();
                Toast.makeText(SettingsActivity.this, now ? "Đã bật phát nền" : "Đã tắt phát nền", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowPip).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = !LivePlayerActivity.pipOn(SettingsActivity.this);
                LivePlayerActivity.setPipOn(SettingsActivity.this, now);
                refresh();
                Toast.makeText(SettingsActivity.this, now ? "Đã bật hình trong hình" : "Đã tắt hình trong hình", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowPerms).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.app.AlertDialog _dlg = new android.app.AlertDialog.Builder(SettingsActivity.this, Theme.dialogTheme(SettingsActivity.this))
                        .setTitle("Quyền hệ thống")
                        .setMessage("Thông báo: " + (Perms.notificationsOn(SettingsActivity.this) ? "Bật" : "Tắt") + "\nHình trong hình: " + (Perms.pipAllowed(SettingsActivity.this) ? "Cho phép" : "Chưa cho phép") + "\nTối ưu pin: " + (Perms.batteryOptIgnored(SettingsActivity.this) ? "Đã bỏ qua (phát nền không bị tắt)" : "Đang bật (có thể tắt nhạc khi tắt màn hình)") + "\n\nMở trang Cài đặt hệ thống nào? (Bấm nút Back để đóng bảng này.)")
                        .setPositiveButton("Cài đặt thông báo", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) { Perms.openNotifSettings(SettingsActivity.this); }
                        })
                        .setNeutralButton("Cài đặt hình trong hình", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) { Perms.openPipSettings(SettingsActivity.this); }
                        })
                        .setNegativeButton("Cài đặt pin", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) { Perms.openBatteryOptSettings(SettingsActivity.this); }
                        })
                        .show();
                Theme.styleDialog(_dlg, SettingsActivity.this);
            }
        });
        refresh();
        applyTheme();
        applySettingsOrientation();
    }
    // v3.23: landscape for the whole app — the settings rows
    // are centred at max 640dp in landscape instead of
    // stretching edge to edge (portrait restores full width).
    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySettingsOrientation();
    }
    private void applySettingsOrientation() {
        View scroll = findViewById(R.id.settingsScroll);
        if (scroll == null || !(scroll instanceof ViewGroup)) return;
        View content = ((ViewGroup) scroll).getChildAt(0);
        if (content == null) return;
        boolean landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) content.getLayoutParams();
        if (lp == null) return;
        float d = getResources().getDisplayMetrics().density;
        if (landscape) {
            int w = getResources().getDisplayMetrics().widthPixels;
            lp.width = Math.min(w, (int) (640 * d));
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        } else {
            lp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
            lp.gravity = android.view.Gravity.NO_GRAVITY;
        }
        content.setLayoutParams(lp);
    }
    // v1.38: re-sync the rows whenever the screen comes back —
    // the user may have just flipped a system permission and
    // returned, and the old screen only refreshed on create
    // and on its own toggles, so it showed stale states.
    @Override protected void onResume() {
        super.onResume();
        refresh();
    }
    // v1.7: theme the settings screen itself — background, bar,
    // header text, back button, and row cards follow Sáng / Tối.
    private void applyTheme() {
        // v3.9: status bar + navigation bar follow the theme.
        Theme.applyStatusBar(this);
        findViewById(R.id.settingsRoot).setBackground(Theme.bgDrawable(this));
        // v1.14: theme change re-tints the bar through its BlurView.
        if (settingsBlur != null) settingsBlur.requestBlur();
        TextView title = findViewById(R.id.txtSettingsTitle);
        if (title != null) title.setTextColor(Theme.headerText(this));
        // v1.11: group headers follow the same header colour
        // (white in dark mode, navy in light mode).
        int[] groups = {R.id.grpDisplay, R.id.grpContent, R.id.grpNotify, R.id.grpBackup, R.id.grpWidget, R.id.grpAbout};
        for (int id : groups) {
            TextView g = findViewById(id);
            if (g != null) g.setTextColor(Theme.headerText(this));
        }
        // v3.12: sources hint + empty note follow the theme.
        TextView sh = findViewById(R.id.txtSourcesHint);
        if (sh != null) sh.setTextColor(Theme.noteText(this));
        TextView sn = findViewById(R.id.txtSourcesNote);
        if (sn != null) sn.setTextColor(Theme.noteText(this));
        TextView back = findViewById(R.id.btnSettingsBack);
        // v1.13: back button plain on the bar, like the main header.
        if (back != null) { back.setBackgroundColor(0x00000000); back.setTextColor(Theme.headerText(this)); }
        int[] rows = {R.id.rowTitles, R.id.rowGlass, R.id.rowNotify, R.id.rowLotteryNotify, R.id.rowLotteryRegions, R.id.rowKeywords, R.id.rowSources, R.id.rowAbout, R.id.rowTheme, R.id.rowBgPlay, R.id.rowPip, R.id.rowAutoplay, R.id.rowAudioOnly, R.id.rowPerms, R.id.rowViewMode, R.id.rowFontSize, R.id.rowBackup, R.id.rowRestore, R.id.rowWidgetAlpha, R.id.rowWidgetCount, R.id.rowWidgetTheme};
        for (int id : rows) {
            View row = findViewById(id);
            if (row == null) continue;
            // v1.42 (C2/Q2): rows follow the main screen's card
            // standard (dark slate + light text in dark mode),
            // not the old light-glass card with hardcoded dark
            // text that made Settings look like a different app.
            // Titles are the first child, states the second.
            row.setBackground(GlassUi.cardBg(this, 28f));
            if (row instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) row;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View ch = g.getChildAt(i);
                    if (ch instanceof TextView) ((TextView) ch).setTextColor(i == 0 ? Theme.bodyText(this) : Theme.noteText(this));
                }
            }
        }
    }
    private void refresh() {
        txtTitles.setText(TranslateUtil.translateTitlesEnabled(this) ? "Bật" : "Tắt");
        txtGlass.setText(GlassUi.MODES[GlassUi.mode(this)]);
        txtNotify.setText(NewsStore.notifyOn(this) ? "Bật" : "Tắt");
        // v3.8: lottery notifications — master state + regions.
        if (txtLotteryNotify != null) txtLotteryNotify.setText(LotteryNotifier.isMasterOn(this) ? "Bật" : "Tắt");
        if (txtLotteryRegions != null) txtLotteryRegions.setText(lotteryRegionsText());
        // v3.29: one feature now — background play IS screen-off
        // play, so the state line says so.
        txtBgPlay.setText(LivePlayerActivity.bgPlayOn(this) ? "Bật — rời app, tắt màn hình vẫn nghe" : "Tắt");
        txtPip.setText(LivePlayerActivity.pipOn(this) ? "Bật" : "Tắt");
        txtPerms.setText("Thông báo: " + (Perms.notificationsOn(this) ? "Bật" : "Tắt") + " • Hình trong hình: " + (Perms.pipAllowed(this) ? "Cho phép" : "Chưa cho phép"));
        // v1.42 (T10): rowSources was the only row without a
        // state line — it now counts enabled/total sources.
        if (txtSourcesState != null) {
            int en = 0, tot = 0;
            try {
                for (Source s : Sources.load(this)) { tot++; if (s.enabled) en++; }
            } catch (Exception ignored) {}
            txtSourcesState.setText(en + "/" + tot + " nguồn đang bật");
        }
        if (txtTheme != null) {
            String cur = Theme.themeModeName(this);
            if (Theme.theme(this) == Theme.SYSTEM)
                cur += " (đang " + (Theme.isLight(this) ? "Sáng" : "Tối") + " theo máy)";
            txtTheme.setText(cur);
        }
        if (txtAutoplayState != null) txtAutoplayState.setText(LivePlayerActivity.autoplayOn(this) ? "Bật" : "Tắt");
        if (txtViewModeState != null) txtViewModeState.setText(Theme.compactView(this) ? "Gọn" : "Thẻ lớn");
        if (txtFontSizeState != null) txtFontSizeState.setText(Theme.fontModeName(this));
        // v3.12: keyword alerts + audio-only + backup rows.
        if (txtKeywordsState != null) {
            java.util.List<String> kws = NewsStore.keywords(this);
            txtKeywordsState.setText(kws.isEmpty() ? "Chưa có từ khóa nào"
                    : kws.size() + " từ khóa: " + android.text.TextUtils.join(", ", kws));
        }
        if (txtAudioOnlyState != null) txtAudioOnlyState.setText(LivePlayerActivity.audioOnlyOn(this) ? "Bật" : "Tắt");
        if (txtBackupState != null) txtBackupState.setText("Xuất nguồn tin + cài đặt ra file");
        if (txtRestoreState != null) txtRestoreState.setText("Nhập file đã sao lưu");
        // v3.14: widget rows.
        if (txtWidgetAlphaState != null) txtWidgetAlphaState.setText(widgetAlphaName(this));
        if (txtWidgetCountState != null) txtWidgetCountState.setText(NewsWidgetProvider.widgetCount(this) + " tin");
        if (txtWidgetThemeState != null) txtWidgetThemeState.setText(widgetThemeName(this));
        buildSourcesList();
        if (sourcesListBox != null) sourcesListBox.setVisibility(sourcesExpanded ? View.VISIBLE : View.GONE);
        if (txtSourcesHint != null) txtSourcesHint.setVisibility(sourcesExpanded ? View.VISIBLE : View.GONE);
    }
    // v3.8: lottery notification rows (same tap-to-toggle
    // pattern as the news notify row).
    private String lotteryRegionsText() {
        // Display order is Nam - Trung - Bắc (v3.5).
        int[] order = {LotteryFetcher.MN, LotteryFetcher.MT, LotteryFetcher.MB};
        java.util.List<String> on = new java.util.ArrayList<>();
        for (int r : order) if (LotteryNotifier.isRegionOn(this, r)) on.add(LotteryFetcher.REGION_NAMES[r]);
        if (on.isEmpty()) return "Chưa chọn đài nào";
        return android.text.TextUtils.join(" • ", on);
    }
    private void toggleLotteryNotify() {
        if (LotteryNotifier.isMasterOn(this)) {
            LotteryNotifier.setMasterOn(this, false);
            LotteryNotifier.cancelAll(this);
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9003);
            return;
        }
        LotteryNotifier.setMasterOn(this, true);
        LotteryNotifier.scheduleAll(this);
        refresh();
        Toast.makeText(this, "Đã bật thông báo xổ số.", Toast.LENGTH_SHORT).show();
    }
    private void showLotteryRegionsDialog() {
        final int[] order = {LotteryFetcher.MN, LotteryFetcher.MT, LotteryFetcher.MB};
        final String[] names = {"Miền Nam", "Miền Trung", "Miền Bắc"};
        boolean[] checked = new boolean[3];
        for (int i = 0; i < 3; i++) checked[i] = LotteryNotifier.isRegionOn(this, order[i]);
        android.app.AlertDialog _dlg = new android.app.AlertDialog.Builder(this, Theme.dialogTheme(this))
                .setTitle("Đài nhận thông báo xổ số")
                .setMultiChoiceItems(names, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                        LotteryNotifier.setRegionOn(SettingsActivity.this, order[which], isChecked);
                        if (isChecked) LotteryNotifier.scheduleRegion(SettingsActivity.this, order[which]);
                        else LotteryNotifier.cancelRegion(SettingsActivity.this, order[which]);
                        refresh();
                    }
                })
                .setPositiveButton("Xong", null)
                .show();
        Theme.styleDialog(_dlg, this);
    }
    private void toggleNotify() {
        if (NewsStore.notifyOn(this)) {
            NewsStore.setNotifyOn(this, false);
            NewsJobService.cancel(this);
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9002);
            return;
        }
        NewsStore.setNotifyOn(this, true);
        NewsJobService.schedule(this);
        refresh();
        Toast.makeText(this, "Đã bật thông báo tin mới.", Toast.LENGTH_SHORT).show();
    }
    // v3.12: Sources live in Settings now — the list is
    // built from SourceAdapter rows (same swipe/toggle/edit/
    // delete behaviour as the old tab; the "close other rows"
    // walk is skipped here because there is no ListView).
    private void buildSourcesList() {
        if (sourcesListBox == null) return;
        sourcesListBox.removeAllViews();
        if (sources == null) sources = Sources.load(this);
        if (sourceAdapter == null) {
            sourceAdapter = new SourceAdapter(this, sources, new java.util.HashMap<String, String>());
            sourceAdapter.setListener(new SourceAdapter.Listener() {
                public void onToggle(Source s) {
                    s.enabled = !s.enabled;
                    Sources.save(SettingsActivity.this, sources);
                    buildSourcesList();
                    refresh();
                }
                public void onEdit(final Source s) { showEditSourceDialog(s); }
                public void onDelete(final Source s) { showDeleteSourceDialog(s); }
            });
        }
        for (int i = 0; i < sources.size(); i++) {
            View row = sourceAdapter.getView(i, null, sourcesListBox);
            sourcesListBox.addView(row);
        }
        if (txtSourcesNote != null) txtSourcesNote.setVisibility(sources.isEmpty() ? View.VISIBLE : View.GONE);
    }
    private void showEditSourceDialog(final Source s) {
        final EditText edName = new EditText(this);
        final EditText edUrl = new EditText(this);
        edName.setText(s.name); edUrl.setText(s.url);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        edName.setHint("Tên nguồn"); edUrl.setHint("URL RSS");
        box.addView(edName);
        // v3.2: a multi-feed outlet has no single URL to edit.
        if (s.feeds.size() == 1) {
            box.addView(edUrl);
        } else {
            TextView note = new TextView(this);
            note.setText("Nguồn này gồm " + s.feeds.size() + " mục tin — chỉ đổi được tên, không sửa từng mục ở đây.");
            note.setTextColor(Theme.noteText(this));
            note.setPadding(0, pad / 2, 0, 0);
            box.addView(note);
        }
        android.app.AlertDialog _dlg = new android.app.AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setTitle("Sửa nguồn")
            .setView(box)
            .setPositiveButton("Lưu", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    String name = edName.getText().toString().trim();
                    if (!name.isEmpty()) s.name = name;
                    if (s.feeds.size() == 1) {
                        String url = edUrl.getText().toString().trim();
                        if (url.isEmpty() || !url.startsWith("http")) {
                            Toast.makeText(SettingsActivity.this, "URL phải bắt đầu bằng http", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        s.url = url;
                        s.feeds.get(0).url = url;
                    }
                    Sources.save(SettingsActivity.this, sources);
                    buildSourcesList();
                    refresh();
                }
            })
            .setNegativeButton("Huỷ", null)
            .show();
        Theme.styleDialog(_dlg, this);
    }
    private void showDeleteSourceDialog(final Source s) {
        android.app.AlertDialog _dlg = new android.app.AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setTitle("Xoá nguồn?")
            .setMessage("Xoá " + s.name + "? Tin từ nguồn này sẽ không còn được tải nữa.")
            .setPositiveButton("Xoá", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    sources.remove(s);
                    Sources.save(SettingsActivity.this, sources);
                    buildSourcesList();
                    refresh();
                    Toast.makeText(SettingsActivity.this, "Đã xoá " + s.name, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Huỷ", null)
            .show();
        Theme.styleDialog(_dlg, this);
    }
    // v3.12: keyword alerts — one keyword per line.
    private void showKeywordsDialog() {
        final EditText ed = new EditText(this);
        ed.setText(android.text.TextUtils.join("\n", NewsStore.keywords(this)));
        ed.setHint("Mỗi dòng một từ khóa — vd:\nTrump\nFed\nlãi suất");
        ed.setMinLines(4);
        ed.setGravity(android.view.Gravity.TOP);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        ed.setPadding(pad, pad, pad, pad);
        android.app.AlertDialog _dlg = new android.app.AlertDialog.Builder(this, Theme.dialogTheme(this))
            .setTitle("Từ khóa cần báo")
            .setMessage("Có tin mới chứa một trong các từ khóa này thì app báo ngay.")
            .setView(ed)
            .setPositiveButton("Lưu", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    java.util.List<String> kws = new java.util.ArrayList<>();
                    for (String line : ed.getText().toString().split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty() && !kws.contains(t)) kws.add(t);
                        if (kws.size() >= 20) break;
                    }
                    NewsStore.setKeywords(SettingsActivity.this, kws);
                    NewsJobService.ensureScheduled(SettingsActivity.this);
                    if (!kws.isEmpty() && Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9004);
                    }
                    refresh();
                    Toast.makeText(SettingsActivity.this, kws.isEmpty() ? "Đã xoá hết từ khóa — không báo nữa" : "Đã lưu " + kws.size() + " từ khóa", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Huỷ", null)
            .show();
        Theme.styleDialog(_dlg, this);
    }
    // v3.14: widget setting names for the Settings rows.
    private static String widgetAlphaName(android.content.Context c) {
        int i = NewsWidgetProvider.widgetAlphaIdx(c);
        return i == 1 ? "Mờ (80%)" : i == 2 ? "Đục (100%)" : "Trong suốt (60%)";
    }
    private static String widgetThemeName(android.content.Context c) {
        int m = NewsWidgetProvider.widgetThemeMode(c);
        return m == 1 ? "Luôn tối" : m == 2 ? "Luôn sáng" : "Theo app";
    }
    // v3.12: audio-only video mode (see LivePlayerActivity).
    private void toggleAudioOnly() {
        boolean now = !LivePlayerActivity.audioOnlyOn(this);
        LivePlayerActivity.setAudioOnlyOn(this, now);
        refresh();
        Toast.makeText(this, now ? "Đã bật chỉ nghe tiếng" : "Đã tắt chỉ nghe tiếng", Toast.LENGTH_SHORT).show();
    }
    // v3.12: settings backup — sources + all prefs as JSON,
    // written/read through the system file picker (no storage
    // permission needed). seen_keys + the transient open_tab
    // are left out on purpose.
    private String buildBackupJson() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject();
        android.content.SharedPreferences p = getSharedPreferences("kenzelections", MODE_PRIVATE);
        org.json.JSONObject prefs = new org.json.JSONObject();
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if ("seen_keys".equals(k) || "open_tab".equals(k)) continue;
            Object v = e.getValue();
            if (v instanceof java.util.Set) continue;
            prefs.put(k, v);
        }
        root.put("app", "Kenz News");
        root.put("version", 1);
        root.put("prefs", prefs);
        return root.toString(2);
    }
    private void applyBackupJson(String text) throws Exception {
        org.json.JSONObject root = new org.json.JSONObject(text);
        org.json.JSONObject prefs = root.getJSONObject("prefs");
        android.content.SharedPreferences.Editor ed = getSharedPreferences("kenzelections", MODE_PRIVATE).edit();
        java.util.Iterator<String> keys = prefs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = prefs.get(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
            else if (v instanceof String) ed.putString(k, (String) v);
        }
        ed.apply();
    }
    private void doBackup() {
        try {
            pendingBackupJson = buildBackupJson();
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(android.content.Intent.EXTRA_TITLE, "kenz-news-sao-luu.json");
            startActivityForResult(i, 9101);
        } catch (Exception e) {
            Toast.makeText(this, "Không tạo được file sao lưu.", Toast.LENGTH_SHORT).show();
        }
    }
    private void doRestore() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        startActivityForResult(i, 9102);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == 9101) {
            try {
                java.io.OutputStream out = getContentResolver().openOutputStream(data.getData());
                out.write(pendingBackupJson.getBytes("UTF-8"));
                out.close();
                Toast.makeText(this, "Đã lưu file sao lưu.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Không ghi được file.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == 9102) {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) buf.write(b, 0, n);
                in.close();
                applyBackupJson(buf.toString("UTF-8"));
                sources = Sources.load(this);
                NewsJobService.ensureScheduled(this);
                LotteryNotifier.scheduleAll(this);
                refresh();
                applyTheme();
                Toast.makeText(this, "Đã khôi phục cài đặt — một số mục áp dụng khi mở lại app.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "File sao lưu không đọc được.", Toast.LENGTH_SHORT).show();
            }
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 9002 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            NewsStore.setNotifyOn(this, true);
            NewsJobService.schedule(this);
            refresh();
        } else if (code == 9002) {
            Toast.makeText(this, "Chưa cấp quyền thông báo.", Toast.LENGTH_SHORT).show();
        } else if (code == 9003 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            // v3.8: the lottery toggle was waiting on this grant.
            LotteryNotifier.setMasterOn(this, true);
            LotteryNotifier.scheduleAll(this);
            refresh();
        } else if (code == 9003) {
            Toast.makeText(this, "Chưa cấp quyền thông báo.", Toast.LENGTH_SHORT).show();
        } else if (code == 9004 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            // v3.12: keyword alerts were waiting on this grant.
            NewsJobService.ensureScheduled(this);
            refresh();
        } else if (code == 9004) {
            Toast.makeText(this, "Chưa cấp quyền thông báo — từ khóa đã lưu nhưng chưa báo được.", Toast.LENGTH_SHORT).show();
        }
    }
}
