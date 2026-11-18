package com.rightnews.live;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
// v1.27: in-app player for LIVE streams. Plays the stream with
// YouTube's OFFICIAL embed player (iframe) inside a WebView —
// the route YouTube itself sanctions for third-party apps, so
// it is not blocked the way extracting raw stream URLs and
// feeding them to a bare player would be (signed URLs, 403s,
// ToS). v1.27 BUG, fixed in v1.28: the embed HTML was loaded
// with base URL https://www.youtube.com/, so the page claimed
// to BE YouTube hosting a YouTube iframe — YouTube rejects
// that invalid embed context with error 152-4 (the exact card
// from the user's 2026-11-04 screenshot; the video itself was
// fine). v1.28 loads it under the app's own id as the origin
// (https://com.rightnews.live/), the documented pattern for
// apps with no website, while the iframe still targets
// youtube.com/embed. No API key is needed, and ads play as on the web,
// which is part of why the route is allowed. Verified
// 2026-11-04: the 3 live streams then running (Fox, NY Post,
// Wash. Examiner) all returned oEmbed HTTP 200, i.e. embedding
// is allowed for them. Two honest limits, both surfaced in the
// UI instead of hidden: (1) if a channel disables embedding
// for a particular stream, the iframe shows YouTube's own
// error — the app cannot detect that from outside the
// cross-origin iframe, so the screen carries a "Mở trong app
// YouTube" button as the fallback. (2) v1.32 adds the
// live-chat pane that v1.27 lacked. The v1.27 worry (chat
// embed needs a real embedding domain) is solved the same
// way as the player: the chat iframe is loaded from a page
// whose origin is the app id (https://com.rightnews.live/),
// and the iframe URL carries embed_domain=com.rightnews.live
// to match. Availability is NOT guessed from the player:
// before showing anything, the app fetches the live_chat page
// itself (Chrome UA, Referer the app origin) and shows the
// pane only if the page carries a liveChatRenderer. Verified
// 2026-11-04 from the VM: a live stream with chat (Sean Le
// TV's election live) returned the renderer, while a regular
// upload returned "Chat is disabled" and no renderer — so the
// check cleanly separates the two cases. If the check fails
// or finds no chat, no pane appears at all (no empty box).
// Reading chat needs no sign-in; posting does, and that
// sign-in stays inside the chat pane (accounts.google.com is
// allowed there). v1.35: background playback + picture-in-
// picture. Background playback is a Settings toggle (default
// ON): when ON, leaving the player (Home, switch apps,
// screen off) no longer pauses the player WebView, and a
// foreground service (PlaybackService) keeps the process
// alive with a "Đang phát trực tiếp" notification — tap it
// to return, "Dừng" to stop. When OFF, the old behaviour
// returns (leaving pauses). The PiP button enters Android
// picture-in-picture (16:9); in PiP only the player shows,
// and playback never pauses there regardless of the toggle.
// The manifest carries supportsPictureInPicture + the
// configChanges that keep rotation/PiP from recreating the
// activity (recreation would reload the WebView mid-stream),
// so rotation is now handled by applyOrientationLayout
// instead of onCreate. Regular video rows still open the
// YouTube app — only LIVE detail taps come here.
public class LivePlayerActivity extends Activity {
    private WebView web;
    private WebView chat;
    private ImageButton btnChat;
    private ImageButton btnAudioOnly;
    // v3.12: audio-only mode — the WebView is INVISIBLE
    // (not GONE: the page stays attached so the audio keeps
    // playing) while the black player area stays in place.
    private boolean audioOnly = false;
    private boolean chatAvailable;
    private boolean chatHidden;
    private View infoPanel;
    private View rootView;
    private View landscapeBtns;
    private View relatedSection;
    private View relatedScroll;
    private android.widget.LinearLayout relatedList;
    private TextView txtRelatedNote;
    private String channelId = "";
    // v2.0 (ý tưởng 1 — hàng đợi + tự phát tiếp): the
    // related list doubles as the queue; backStack holds
    // previous videos so Back returns to them instead of
    // exiting after an in-player switch.
    private java.util.List<NewsItem> queueItems = new java.util.ArrayList<>();
    private java.util.List<String[]> backStack = new java.util.ArrayList<>();
    private ImageButton btnLandPip;
    private WebChromeClient chrome;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean landscape;
    private String vid;
    private String channelName;
    private String streamTitle;
    private boolean bgPlayOn;
    private boolean leavingToExternal;
    private boolean inPip;
    // v3.29: PiP controls + playback state. The PiP window
    // and the media notification both drive the player
    // through these broadcast actions (same process, so a
    // package-scoped broadcast reaches the dynamic receiver
    // below); the WebView's own state changes flow back the
    // other way via the JS bridge -> setPlaying.
    public static final String ACTION_PLAY = "com.rightnews.live.PLAYER_PLAY";
    public static final String ACTION_PAUSE = "com.rightnews.live.PLAYER_PAUSE";
    public static final String ACTION_TOGGLE = "com.rightnews.live.PLAYER_TOGGLE";
    public static final String ACTION_NEXT = "com.rightnews.live.PLAYER_NEXT";
    public static final String ACTION_STOP = "com.rightnews.live.PLAYER_STOP";
    private boolean playing = true;
    private android.content.BroadcastReceiver playerActionReceiver;
    private android.view.GestureDetector swipeDetector;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_player);
        Theme.preferHighRefreshRate(this);
        final View root = findViewById(R.id.playerRoot);
        rootView = findViewById(R.id.contentLay);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
                return insets;
            }
        });
        String videoId = getIntent().getStringExtra("videoId");
        String channel = getIntent().getStringExtra("channel");
        String title = getIntent().getStringExtra("title");
        // The id comes from LiveChecker (11 chars from a canonical
        // watch URL), but never trust an intent extra blindly —
        // it is interpolated into the embed URL below.
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) { finish(); return; }
        // The notification's Dừng action routes here with
        // stop_playback: finish before anything loads, so a
        // stale notification can never start playback.
        if (getIntent().getBooleanExtra("stop_playback", false)) { finish(); return; }
        vid = videoId;
        channelName = channel;
        String cidExtra = getIntent().getStringExtra("channelId");
        if (cidExtra != null) channelId = cidExtra;
        streamTitle = title;
        bgPlayOn = bgPlayOn(this);
        TextView ch = findViewById(R.id.txtPlayerChannel);
        ch.setText(channel != null && !channel.isEmpty() ? channel : "Đang trực tiếp");
        TextView ti = findViewById(R.id.txtPlayerTitle);
        ti.setText(displayPlayerTitle(title));
        ImageButton btnOpen = findViewById(R.id.btnOpenYoutube);
        btnOpen.setBackground(Theme.pillDrawable(this));
        btnOpen.setColorFilter(Theme.pillText(this));
        btnOpen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openInYouTubeApp(vid); }
        });
        btnChat = findViewById(R.id.btnToggleChat);
        btnChat.setBackground(Theme.pillDrawable(this));
        btnChat.setColorFilter(Theme.pillText(this));
        btnChat.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                chatHidden = !chatHidden;
                chat.setVisibility(chatHidden ? View.GONE : View.VISIBLE);
                btnChat.setImageResource(chatHidden ? R.drawable.ic_chat_off : R.drawable.ic_chat);
                btnChat.setContentDescription(chatHidden ? "Hiện chat" : "Ẩn chat");
            }
        });
        ImageButton btnPip = findViewById(R.id.btnPip);
        btnPip.setBackground(Theme.pillDrawable(this));
        btnPip.setColorFilter(Theme.pillText(this));
        btnPip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { enterPip(); }
        });
        // v1.38: the PiP button follows the Settings toggle
        // (default ON); when the toggle is off the button hides
        // instead of failing on tap.
        btnPip.setVisibility(pipOn(this) ? View.VISIBLE : View.GONE);
        // v3.12: audio-only toggle (also a Settings row).
        btnAudioOnly = findViewById(R.id.btnAudioOnly);
        btnAudioOnly.setBackground(Theme.pillDrawable(this));
        btnAudioOnly.setColorFilter(Theme.pillText(this));
        btnAudioOnly.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = !audioOnly;
                setAudioOnlyOn(LivePlayerActivity.this, now);
                setAudioOnly(now, true);
            }
        });
        // v3.22: floating PiP for landscape only, where
        // infoPanel is hidden (see applyOrientationLayout).
        // No back/X button on the player any more (the v3.21
        // back chip covered the video) — system back exits.
        landscapeBtns = findViewById(R.id.landscapeBtns);
        btnLandPip = findViewById(R.id.btnLandPip);
        if (btnLandPip != null) {
            btnLandPip.setBackground(Theme.pillDrawable(this));
            btnLandPip.setColorFilter(Theme.pillText(this));
            btnLandPip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { enterPip(); }
            });
            btnLandPip.setVisibility(pipOn(this) ? View.VISIBLE : View.GONE);
        }
        relatedSection = findViewById(R.id.relatedSection);
        relatedScroll = findViewById(R.id.relatedScroll);
        relatedList = findViewById(R.id.relatedList);
        txtRelatedNote = findViewById(R.id.txtRelatedNote);
        web = findViewById(R.id.webPlayer);
        chat = findViewById(R.id.webChat);
        infoPanel = findViewById(R.id.infoPanel);
        applyOrientationLayout();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // The user already tapped the LIVE card to get here, so
        // autoplay is their gesture — do not make them tap play
        // a second time inside the player.
        s.setMediaPlaybackRequiresUserGesture(false);
        // A Chrome UA, not the WebView default (which carries a
        // "wv" marker YouTube sometimes treats as a lesser client).
        s.setUserAgentString(TranslateUtil.chromeUa());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return handleUrl(url); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { return handleUrl(req.getUrl().toString()); }
        });
        chrome = new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view; customCallback = cb;
                ViewGroup decor = (ViewGroup) getWindow().getDecorView();
                decor.addView(customView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                infoPanel.setVisibility(View.GONE);
                if (landscapeBtns != null) landscapeBtns.setVisibility(View.GONE);
                if (relatedSection != null) relatedSection.setVisibility(View.GONE);
                chat.setVisibility(View.GONE);
            }
            @Override public void onHideCustomView() {
                if (customView == null) return;
                ViewGroup decor = (ViewGroup) getWindow().getDecorView();
                decor.removeView(customView);
                customView = null;
                web.setVisibility(audioOnly ? View.INVISIBLE : View.VISIBLE);
                infoPanel.setVisibility(landscape ? View.GONE : View.VISIBLE);
                if (landscapeBtns != null) landscapeBtns.setVisibility(landscape ? View.VISIBLE : View.GONE);
                chat.setVisibility(chatAvailable && !chatHidden ? View.VISIBLE : View.GONE);
                updateRelatedVisibility();
                if (customCallback != null) { customCallback.onCustomViewHidden(); customCallback = null; }
            }
        };
        web.setWebChromeClient(chrome);
        web.addJavascriptInterface(new PlayerBridge(), "Android");
        loadEmbed();
        // v1.32: live chat. Same origin trick as the player —
        // the chat iframe lives on a page whose origin is the
        // app id, and embed_domain matches it. The pane stays
        // hidden until the availability check below confirms
        // this stream actually has chat, so a chat-less stream
        // never shows an empty box.
        WebSettings cs = chat.getSettings();
        cs.setJavaScriptEnabled(true);
        cs.setDomStorageEnabled(true);
        cs.setUserAgentString(TranslateUtil.chromeUa());
        chat.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return handleChatUrl(url); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { return handleChatUrl(req.getUrl().toString()); }
        });
        startChatCheck();
        // v3.12: honour the audio-only pref on open.
        setAudioOnly(audioOnlyOn(this), false);
        // v3.16: the player follows the app theme (see below).
        applyPlayerTheme();
        // v3.29: PiP + notification controls, swipe-down to
        // PiP, and the playback service from the first second
        // (not onStop — see startPlaybackService).
        registerPlayerActions();
        setupSwipeDownPip();
        startPlaybackService();
        updatePipParams();
    }
    // v3.16: the player follows the APP theme now. It used to be
    // black always (v1.27) — which is why it looked out of sync
    // once the app went light: light pills + theme-coloured bits
    // on a black screen (his 13:24 screenshot). Background is
    // the app's own gradient/black, the status bar follows, the
    // info + related panels become the app's cards (cardBg, the
    // same background the news cards use), and every text uses
    // the theme colours — so the player reads as another app
    // screen, not a separate black app. The video itself stays
    // black inside its 16:9 box, like YouTube's own light
    // watch page. Called once in onCreate; a system theme flip
    // recreates this activity (uiMode is not in configChanges)
    // and re-runs it with the new theme.
    private void applyPlayerTheme() {
        View root = findViewById(R.id.playerRoot);
        if (root != null) root.setBackground(Theme.bgDrawable(this));
        Theme.applyStatusBar(this);
        float d = getResources().getDisplayMetrics().density;
        int m = (int) (12 * d);
        if (infoPanel != null) {
            infoPanel.setBackground(GlassUi.cardBg(this, 28f));
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) infoPanel.getLayoutParams();
            if (lp != null) { lp.setMargins(m, m, m, m); infoPanel.setLayoutParams(lp); }
        }
        if (relatedSection != null) {
            relatedSection.setBackground(GlassUi.cardBg(this, 28f));
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) relatedSection.getLayoutParams();
            if (lp != null) { lp.setMargins(m, 0, m, m); relatedSection.setLayoutParams(lp); }
        }
        TextView ch = findViewById(R.id.txtPlayerChannel);
        if (ch != null) ch.setTextColor(Theme.headerText(this));
        TextView ti = findViewById(R.id.txtPlayerTitle);
        if (ti != null) ti.setTextColor(Theme.bodyText(this));
        TextView rh = findViewById(R.id.txtRelatedHead);
        if (rh != null) rh.setTextColor(Theme.headerText(this));
        if (txtRelatedNote != null) txtRelatedNote.setTextColor(Theme.subText(this));
    }
    private void startChatCheck() {
        new Thread(new Runnable() {
            public void run() {
                final boolean avail = hasLiveChat(vid);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (!avail) { updateRelatedVisibility(); loadRelated(); return; }
                        chatAvailable = true;
                        chat.setVisibility(View.VISIBLE);
                        btnChat.setVisibility(View.VISIBLE);
                        // v1.42 (T9): the chat frame is always
                        // dark now. Before, the pane surround
                        // followed the app theme while YouTube's
                        // iframe content stayed dark (v1.34's
                        // known limit, no param flips it), so
                        // light mode showed three tones at once:
                        // black player + white frame + dark chat.
                        // A uniformly dark player area is the
                        // only consistent look the embed allows.
                        chat.setBackgroundColor(0xFF000000);
                        String chatHtml = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                                + "<style>html,body{margin:0;padding:0;background:#000000;height:100%}iframe{width:100%;height:100%;border:0}</style></head>"
                                + "<body><iframe src=\"https://www.youtube.com/live_chat?v=" + vid + "&embed_domain=com.rightnews.live&dark_theme=1\" "
                                + "referrerpolicy=\"strict-origin-when-cross-origin\"></iframe></body></html>";
                        chat.loadDataWithBaseURL("https://com.rightnews.live/", chatHtml, "text/html", "UTF-8", null);
                        updateRelatedVisibility();
                    }
                });
            }
        }).start();
    }
    // v2.0 (ý tưởng 1): the embed carries enablejsapi=1
    // plus the official IFrame API, so the player learns
    // when a video ENDS (state 0) and can advance the
    // queue. The base URL / origin trick from v1.28 is
    // untouched — only enablejsapi and the API script are
    // added. If the API script ever fails to load, playback
    // still works; only auto-advance stops.
    // v3.29: the player object is kept in window.ytPlayer
    // (play/pause from the PiP buttons + notification) and
    // states 1/2 (playing/paused) are reported too, so the
    // PiP + notification icons follow the real state.
    private void loadEmbed() {
        String html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;padding:0;background:#000;height:100%}iframe{width:100%;height:100%;border:0}</style></head>"
                + "<body><iframe id=\"ytplayer\" src=\"https://www.youtube.com/embed/" + vid + "?autoplay=1&playsinline=1&rel=0&enablejsapi=1\" "
                + "allow=\"autoplay; encrypted-media; fullscreen; picture-in-picture\" referrerpolicy=\"strict-origin-when-cross-origin\" allowfullscreen></iframe>"
                + "<script src=\"https://www.youtube.com/iframe_api\"></script>"
                + "<script>var ytPlayer=null;function onYouTubeIframeAPIReady(){ytPlayer=new YT.Player('ytplayer',{events:{'onStateChange':onPlayerStateChange}});}"
                + "function onPlayerStateChange(e){Android.onPlayerState(e.data);}</script></body></html>";
        web.loadDataWithBaseURL("https://com.rightnews.live/", html, "text/html", "UTF-8", null);
    }
    public class PlayerBridge {
        // v3.29: one callback for every player state — 0 ended
        // (advance the queue), 1 playing / 2 paused (sync the
        // PiP buttons + the media notification). Buffering (3)
        // and the rest leave the state as it is.
        @JavascriptInterface public void onPlayerState(final int state) {
            runOnUiThread(new Runnable() { public void run() {
                if (state == 0) playNext();
                else if (state == 1) setPlaying(true);
                else if (state == 2) setPlaying(false);
            } });
        }
    }
    private void playNext() {
        if (!autoplayOn(this)) return;
        if (queueItems == null || queueItems.isEmpty()) return;
        switchTo(queueItems.get(0), true);
    }
    private void switchTo(NewsItem it, boolean push) {
        String id = videoIdFromLink(it.link);
        if (id == null) return;
        switchToRaw(id, it.source, it.title, it.channelId == null ? "" : it.channelId, push);
    }
    // v3.15: the player title in Vietnamese when the cache has
    // it (same key the lists use — the canonical watch URL).
    // Cache-only: never waits on the network, so opening the
    // player stays instant; a miss just keeps the original
    // title. streamTitle itself stays raw (it feeds the back
    // stack + the playback notification).
    private String displayPlayerTitle(String raw) {
        if (raw == null || raw.isEmpty()) return "Đang trực tiếp";
        return TranslateUtil.displayTitleCacheOnly(this, "https://www.youtube.com/watch?v=" + vid, raw);
    }
    private void switchToRaw(String id, String chan, String title, String cid, boolean push) {
        if (push) backStack.add(new String[]{vid, channelName, streamTitle, channelId});
        if (customView != null) chrome.onHideCustomView();
        vid = id;
        channelName = chan;
        streamTitle = title;
        channelId = cid == null ? "" : cid;
        TextView ch = findViewById(R.id.txtPlayerChannel);
        if (ch != null) ch.setText(chan != null && !chan.isEmpty() ? chan : "Đang trực tiếp");
        TextView ti = findViewById(R.id.txtPlayerTitle);
        if (ti != null) ti.setText(displayPlayerTitle(title));
        sendServiceTitle();
        chatAvailable = false;
        chatHidden = false;
        if (chat != null) chat.setVisibility(View.GONE);
        if (btnChat != null) btnChat.setVisibility(View.GONE);
        queueItems.clear();
        if (relatedList != null) relatedList.removeAllViews();
        if (relatedScroll != null) relatedScroll.setVisibility(View.GONE);
        if (txtRelatedNote != null) txtRelatedNote.setText("Đang tải video liên quan…");
        loadEmbed();
        startChatCheck();
    }
    // v1.47: related videos for a non-live video = the same
    // channel's other videos via its mobile /videos page
    // (the route proven on his connection in v1.44).
    // Runs once per player open; rows persist across
    // rotations because rotation does not recreate this
    // activity. Tapping a row opens that video in a new
    // player on top, so Back returns to this one.
    private void loadRelated() {
        if (txtRelatedNote == null) return;
        final String cid = channelId == null ? "" : channelId;
        if (cid.isEmpty()) { txtRelatedNote.setText("Không xác định được kênh của video này."); return; }
        txtRelatedNote.setText("Đang tải video liên quan…");
        new Thread(new Runnable() {
            public void run() {
                java.util.List<NewsItem> rel = null;
                try { rel = YoutubeChannelFetcher.fetchMobile(cid, channelName); } catch (Exception ignored) {}
                final java.util.List<NewsItem> out = new java.util.ArrayList<>();
                if (rel != null) {
                    java.util.Set<String> seenIds = new java.util.HashSet<>();
                    for (NewsItem it : rel) {
                        String id = videoIdFromLink(it.link);
                        if (id == null || id.equals(vid) || !seenIds.add(id)) continue;
                        if (TranslateUtil.translateTitlesEnabled(LivePlayerActivity.this)) it.title = TranslateUtil.displayTitleSync(LivePlayerActivity.this, it.link, it.title);
                        out.add(it);
                        if (out.size() >= 10) break;
                    }
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (out.isEmpty()) { txtRelatedNote.setText("Không tải được video liên quan — kiểm tra mạng rồi mở lại video."); return; }
                        queueItems = new java.util.ArrayList<>(out);
                        txtRelatedNote.setText("Cùng kênh " + channelName + (autoplayOn(LivePlayerActivity.this) && !out.isEmpty() ? " • Tiếp theo: " + out.get(0).title : ""));
                        buildRelatedRows(out);
                        if (relatedScroll != null) relatedScroll.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }
    private static String videoIdFromLink(String url) {
        if (url == null) return null;
        int v = url.indexOf("v=");
        if (v >= 0) {
            int end = url.indexOf('&', v + 2);
            if (end < 0) end = url.indexOf('#', v + 2);
            return url.substring(v + 2, end < 0 ? url.length() : end);
        }
        if (url.contains("youtu.be/")) {
            int s = url.indexOf("youtu.be/") + 9;
            int end = url.indexOf('?', s);
            if (end < 0) end = url.indexOf('#', s);
            return url.substring(s, end < 0 ? url.length() : end);
        }
        return null;
    }
    private void buildRelatedRows(java.util.List<NewsItem> items) {
        if (relatedList == null) return;
        relatedList.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (final NewsItem it : items) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            // v3.15: touch feedback (faint wash while pressed);
            // v3.16: the wash follows the theme (white on dark,
            // navy on light — a white wash is invisible on light).
            row.setBackgroundResource(Theme.isLight(this) ? R.drawable.related_row_bg_light : R.drawable.related_row_bg);
            android.widget.ImageView thumb = new android.widget.ImageView(this);
            thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            // v3.15: rounded 16dp corners like the news-list
            // thumbs (thumb_round already exists for this).
            thumb.setBackgroundResource(R.drawable.thumb_round);
            thumb.setClipToOutline(true);
            thumb.setLayoutParams(new android.widget.LinearLayout.LayoutParams((int) (112 * d), (int) (63 * d)));
            ImageLoader.load(thumb, it.imageUrl);
            android.widget.LinearLayout texts = new android.widget.LinearLayout(this);
            texts.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = (int) (8 * d);
            texts.setLayoutParams(tp);
            TextView title = new TextView(this);
            title.setTextSize(14);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            // v3.16: theme colours again — v3.15 pinned these to
            // fixed white because the player was black always;
            // the player now follows the app theme (see
            // applyPlayerTheme), so the theme colours are
            // correct on both backgrounds.
            title.setTextColor(Theme.bodyText(this));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setText(it.title);
            TextView meta = new TextView(this);
            meta.setTextSize(12);
            meta.setTextColor(Theme.noteText(this));
            String when = it.time > 0 ? new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(it.time)) : "";
            meta.setText(it.source + (when.isEmpty() ? "" : " • " + when));
            texts.addView(title);
            texts.addView(meta);
            row.addView(thumb);
            row.addView(texts);
            final String rid = videoIdFromLink(it.link);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // v2.0: switch in place (queue) instead of
                    // stacking a new player activity; Back
                    // returns via backStack (see onBackPressed).
                    switchTo(it, true);
                }
            });
            relatedList.addView(row);
        }
    }
    // v1.47: the related section shows only for non-live
    // videos (live videos keep the live-chat pane instead)
    // and only in portrait — landscape keeps player + chat.
    private void updateRelatedVisibility() {
        if (relatedSection == null) return;
        relatedSection.setVisibility(!landscape && !chatAvailable ? View.VISIBLE : View.GONE);
    }
    // v1.35: rotation no longer recreates this activity (the
    // manifest's configChanges say so, because recreation
    // would reload the WebView mid-stream and break PiP), so
    // this re-applies the portrait/landscape arrangement on
    // every rotation and on PiP exit. Portrait: player 16:9
    // on top, info, chat filling the rest. Landscape: info
    // hidden, player left + chat right (the chat toggle is
    // only reachable in portrait — the chat simply stays as
    // it is in landscape).
    private void applyOrientationLayout() {
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout rootLay = (LinearLayout) rootView;
        if (landscape) {
            infoPanel.setVisibility(View.GONE);
            if (landscapeBtns != null) landscapeBtns.setVisibility(View.VISIBLE);
            if (btnLandPip != null) btnLandPip.setVisibility(pipOn(this) ? View.VISIBLE : View.GONE);
            rootLay.setOrientation(LinearLayout.HORIZONTAL);
            web.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 2f));
            chat.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        } else {
            infoPanel.setVisibility(View.VISIBLE);
            if (landscapeBtns != null) landscapeBtns.setVisibility(View.GONE);
            rootLay.setOrientation(LinearLayout.VERTICAL);
            int w = getResources().getDisplayMetrics().widthPixels;
            web.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (w * 9f / 16f)));
            // Chat params must be reset too: after a landscape
            // stint they are horizontal (0 width), not the XML
            // portrait params.
            chat.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        updateRelatedVisibility();
    }
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (inPip) return;
        applyOrientationLayout();
    }
    // v3.29: PiP entry, now from four places like YouTube —
    // the PiP button, a swipe down on the video, Back, and
    // Home (auto-enter on API 31+ plus onUserLeaveHint as
    // the fallback below it). The PiP window shows the
    // player only: info + chat hide here and come back on
    // exit. Its action buttons are YouTube's: play/pause
    // (icon follows the real state) + next; the system
    // supplies the close X itself.
    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipOn(this));
        try {
            java.util.List<android.app.RemoteAction> acts = new java.util.ArrayList<>();
            android.app.PendingIntent togglePi = android.app.PendingIntent.getBroadcast(this, 20,
                    new Intent(ACTION_TOGGLE).setPackage(getPackageName()),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            acts.add(new android.app.RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(this,
                            playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                    playing ? "Tạm dừng" : "Phát", playing ? "Tạm dừng" : "Phát", togglePi));
            android.app.PendingIntent nextPi = android.app.PendingIntent.getBroadcast(this, 21,
                    new Intent(ACTION_NEXT).setPackage(getPackageName()),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            acts.add(new android.app.RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    "Tiếp theo", "Tiếp theo", nextPi));
            b.setActions(acts);
        } catch (Exception ignored) {}
        return b.build();
    }
    // Registers the params (aspect + auto-enter + actions)
    // for the next entry and, while in PiP, refreshes the
    // live window's buttons.
    private void updatePipParams() {
        try { setPictureInPictureParams(buildPipParams()); } catch (Exception ignored) {}
    }
    private boolean tryEnterPip(boolean showDialogOnFail) {
        // v3.12: a PiP window is a video window — show the
        // picture again for it (the pref is untouched, so
        // leaving PiP restores audio-only).
        if (audioOnly) setAudioOnly(false, false);
        try {
            if (enterPictureInPictureMode(buildPipParams())) return true;
            if (!showDialogOnFail) return false;
            // v1.38: a false return means the SYSTEM-level
            // PiP access for the app is off — a special
            // access, not a runtime permission, which is why
            // "granted all permissions" still failed (his
            // 2026-11-04 case). The old toast only said
            // "check system settings"; this dialog opens
            // that exact settings page.
            AlertDialog _dlg = new AlertDialog.Builder(this, Theme.dialogTheme(this))
                    .setTitle("Không vào được Hình trong hình")
                    .setMessage("Quyền Hình trong hình trong Cài đặt hệ thống của máy đang tắt. Mở Cài đặt hệ thống để bật cho Kenz News?")
                    .setPositiveButton("Mở Cài đặt hệ thống", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { Perms.openPipSettings(LivePlayerActivity.this); }
                    })
                    .setNegativeButton("Đóng", null)
                    .show();
            Theme.styleDialog(_dlg, this);
            return false;
        } catch (Exception e) {
            if (showDialogOnFail) Toast.makeText(this, "Thiết bị này không hỗ trợ Hình trong hình.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }
    private void enterPip() { tryEnterPip(true); }
    @Override public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPip = isInPictureInPictureMode;
        if (isInPictureInPictureMode) {
            updatePipParams();
            infoPanel.setVisibility(View.GONE);
            if (landscapeBtns != null) landscapeBtns.setVisibility(View.GONE);
            if (relatedSection != null) relatedSection.setVisibility(View.GONE);
            chat.setVisibility(View.GONE);
            web.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        } else {
            applyOrientationLayout();
            setAudioOnly(audioOnlyOn(this), false);
            chat.setVisibility(chatAvailable && !chatHidden ? View.VISIBLE : View.GONE);
        }
    }
    // The notification's reopen/Dừng intents land here thanks
    // to singleTop: reopen just brings the player forward
    // (playback untouched); Dừng finishes it, and onDestroy
    // stops the service, so the audio stops with it.
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("stop_playback", false)) finish();
    }
    // Background-playback toggle, shared with Settings. The
    // pref lives in the same kenzelections file as the theme.
    // Default ON: the user ordered the feature, so it works
    // out of the box until they switch it off.
    public static boolean bgPlayOn(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean("bg_play", true);
    }
    // v1.38: PiP toggle, default ON, next to "Phát video nền"
    // in Settings (his 2026-11-04 order: Settings showed no PiP
    // on/off at all).
    public static boolean pipOn(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean("pip_enabled", true);
    }
    public static void setPipOn(Context c, boolean on) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean("pip_enabled", on).apply();
    }
    public static void setBgPlayOn(Context c, boolean on) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean("bg_play", on).apply();
    }
    // v3.12: audio-only toggle, default OFF, next to
    // "Tự phát video tiếp theo" in Settings.
    public static boolean audioOnlyOn(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean("audio_only", false);
    }
    public static void setAudioOnlyOn(Context c, boolean on) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean("audio_only", on).apply();
    }
    // v3.12: INVISIBLE (not GONE) — the page stays attached
    // so the audio keeps playing; the black area just shows
    // no picture. The icon flips so the state is visible.
    private void setAudioOnly(boolean on, boolean toast) {
        audioOnly = on;
        if (web != null) web.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        if (btnAudioOnly != null) {
            btnAudioOnly.setImageResource(on ? R.drawable.ic_audio_off : R.drawable.ic_audio);
            btnAudioOnly.setContentDescription(on ? "Hiện hình lại" : "Chỉ nghe tiếng");
        }
        if (on && toast) Toast.makeText(this, "Đang chỉ nghe tiếng — bấm nút loa để hiện hình lại", Toast.LENGTH_SHORT).show();
    }
    // v2.0 (ý tưởng 1): autoplay toggle, default ON, next
    // to "Phát video nền" in Settings.
    public static boolean autoplayOn(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean("autoplay_next", true);
    }
    public static void setAutoplayOn(Context c, boolean on) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean("autoplay_next", on).apply();
    }
    // v3.29: the service starts when the player OPENS (not in
    // onStop) — it is already a foreground service before
    // Home is pressed or the screen goes off, so there is no
    // background-start race, and the wake lock is held from
    // the first second. The display title (Vietnamese when
    // the cache has it) is what the notification shows.
    private void startPlaybackService() {
        try {
            Intent it = new Intent(this, PlaybackService.class);
            it.setAction(PlaybackService.ACTION_START);
            it.putExtra("videoId", vid);
            it.putExtra(PlaybackService.EXTRA_CHANNEL, channelName);
            it.putExtra(PlaybackService.EXTRA_TITLE, displayPlayerTitle(streamTitle));
            startForegroundService(it);
        } catch (Exception ignored) {
            // Without the service the audio still plays while
            // the process lives; the service only guards
            // against the system killing it.
        }
    }
    // v3.29: the player-side half of the PiP + notification
    // controls. The receiver lives as long as the player, so
    // PiP actions (which only exist while the player lives)
    // and the service's forwards both land here.
    private void registerPlayerActions() {
        try {
            playerActionReceiver = new android.content.BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    if (i == null) return;
                    String a = i.getAction();
                    if (ACTION_TOGGLE.equals(a)) togglePlayPause();
                    else if (ACTION_PLAY.equals(a)) setPaused(false);
                    else if (ACTION_PAUSE.equals(a)) setPaused(true);
                    else if (ACTION_NEXT.equals(a)) playNext();
                    else if (ACTION_STOP.equals(a)) finish();
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(ACTION_TOGGLE);
            f.addAction(ACTION_PLAY);
            f.addAction(ACTION_PAUSE);
            f.addAction(ACTION_NEXT);
            f.addAction(ACTION_STOP);
            registerReceiver(playerActionReceiver, f);
        } catch (Exception ignored) {}
    }
    // v3.29: swipe down on the video = PiP, the YouTube
    // gesture. A fling only (fast, mostly vertical) — taps
    // and horizontal seeks pass through untouched, and the
    // listener returns false so the WebView still gets every
    // touch. Chat/related scrolling never triggers it: the
    // detector sits on the player WebView only.
    private void setupSwipeDownPip() {
        try {
            final float minDy = 100 * getResources().getDisplayMetrics().density;
            swipeDetector = new android.view.GestureDetector(this,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                    if (e1 == null || e2 == null) return false;
                    float dy = e2.getY() - e1.getY();
                    float dx = e2.getX() - e1.getX();
                    if (dy > minDy && Math.abs(dy) > Math.abs(dx) * 1.5f && vy > 800) {
                        if (pipOn(LivePlayerActivity.this) && !inPip && customView == null) {
                            tryEnterPip(false);
                            return true;
                        }
                    }
                    return false;
                }
            });
            web.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    if (swipeDetector != null) swipeDetector.onTouchEvent(e);
                    return false;
                }
            });
        } catch (Exception ignored) {}
    }
    // v3.29: play/pause through the iframe API. The player
    // object is kept in window.ytPlayer (see loadEmbed); the
    // state change it causes comes back via the bridge and
    // updates the PiP actions + the notification, so the
    // icons never drift from the real state.
    private void togglePlayPause() { setPaused(playing); }
    private void setPaused(boolean pause) {
        try {
            if (web == null) return;
            web.evaluateJavascript(pause ? "if(window.ytPlayer){ytPlayer.pauseVideo();}"
                    : "if(window.ytPlayer){ytPlayer.playVideo();}", null);
        } catch (Exception ignored) {}
    }
    private void setPlaying(boolean p) {
        if (playing == p) return;
        playing = p;
        updatePipParams();
        sendServiceUpdate();
    }
    private void sendServiceUpdate() {
        try {
            Intent it = new Intent(this, PlaybackService.class);
            it.setAction(PlaybackService.ACTION_UPDATE_STATE);
            it.putExtra(PlaybackService.EXTRA_PLAYING, playing);
            startService(it);
        } catch (Exception ignored) {}
    }
    private void sendServiceTitle() {
        try {
            Intent it = new Intent(this, PlaybackService.class);
            it.setAction(PlaybackService.ACTION_UPDATE_TITLE);
            it.putExtra("videoId", vid);
            it.putExtra(PlaybackService.EXTRA_CHANNEL, channelName);
            it.putExtra(PlaybackService.EXTRA_TITLE, displayPlayerTitle(streamTitle));
            startService(it);
        } catch (Exception ignored) {}
    }
    private void stopPlaybackService() {
        try { stopService(new Intent(this, PlaybackService.class)); } catch (Exception ignored) {}
    }
    // The chat pane's own navigations stay inside (the chat
    // page itself, and Google sign-in for posting). Anything
    // else — a channel link tapped inside chat, a shared link —
    // opens externally, so a stray tap never navigates the
    // chat pane away.
    private boolean handleChatUrl(String url) {
        if (url != null && (url.startsWith("https://www.youtube.com/live_chat")
                || url.startsWith("https://accounts.google.com/"))) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        return true;
    }
    // Fetches the live_chat embed page for this stream and
    // reports whether it carries a live-chat renderer. A live
    // stream with chat returns the renderer; a stream with
    // chat disabled (or a non-live video) returns a "Chat is
    // disabled" page with no renderer. Capped at 512KB — the
    // renderer marker sits around 72KB in. Any failure
    // (network, timeout) reports no chat: showing nothing is
    // better than an empty pane, and the YouTube-app button
    // still gives the user full chat there.
    private static boolean hasLiveChat(String vid) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://www.youtube.com/live_chat?v=" + vid + "&embed_domain=com.rightnews.live").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", TranslateUtil.chromeUa());
            c.setRequestProperty("Referer", "https://com.rightnews.live/");
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int total = 0, n;
            while ((n = in.read(buf)) != -1 && total < 524288) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                total += n;
            }
            return sb.indexOf("liveChatRenderer") >= 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
    // Only the embed page itself stays in the WebView. Any other
    // navigation (e.g. tapping the YouTube logo inside the
    // player, which goes to a watch page) opens externally, so
    // a stray tap can never navigate the player away.
    private boolean handleUrl(String url) {
        if (url != null && url.startsWith("https://www.youtube.com/embed/")) return false;
        // Leaving for an external page: do NOT keep our player
        // running behind it, or the two audios would overlap.
        leavingToExternal = true;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        return true;
    }
    // Same YouTube-app-or-web path as MainActivity.openVideo,
    // duplicated here because that method is private to Main.
    private void openInYouTubeApp(String id) {
        leavingToExternal = true;
        try {
            getPackageManager().getPackageInfo("com.google.android.youtube", 0);
            Intent app = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id));
            app.setPackage("com.google.android.youtube");
            startActivity(app);
            return;
        } catch (Exception ignored) {}
        try {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id));
            try { getPackageManager().getPackageInfo("com.android.chrome", 0); web.setPackage("com.android.chrome"); }
            catch (Exception ignored2) {}
            startActivity(web);
        } catch (Exception ignored) {}
    }
    // v3.29: Home / recents shrinks to PiP like YouTube
    // (auto-enter on API 31+ does the same; this is the
    // fallback for older phones). Guarded: never when
    // leaving for the YouTube app / an external page, never
    // while a fullscreen video is showing, never twice.
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (pipOn(this) && !leavingToExternal && !isFinishing() && !inPip && customView == null) tryEnterPip(false);
    }
    @Override protected void onPause() {
        super.onPause();
        // PiP must never pause: pausing would freeze the small
        // window. With background playback ON, the player keeps
        // playing and only the chat pane pauses (no point
        // polling chat nobody sees). Otherwise the old
        // behaviour: leaving pauses everything.
        if (inPip) return;
        if (bgPlayOn && !leavingToExternal) {
            if (chat != null) chat.onPause();
            return;
        }
        if (web != null) web.onPause();
        if (chat != null) chat.onPause();
    }
    @Override protected void onStop() {
        super.onStop();
        // v3.29: nothing to do here any more — the service
        // started in onCreate and stays up for the whole
        // player lifetime (foreground, background, PiP,
        // screen off), so there is no onStop race to win.
    }
    @Override protected void onResume() {
        super.onResume();
        // v3.29: re-assert the service (it resurrects it if
        // the system killed it while the player was away; a
        // no-op rebuild of the same notification otherwise).
        if (!isFinishing()) startPlaybackService();
        leavingToExternal = false;
        if (web != null) web.onResume();
        if (chat != null) chat.onResume();
    }
    @Override protected void onDestroy() {
        try { if (playerActionReceiver != null) unregisterReceiver(playerActionReceiver); } catch (Exception ignored) {}
        stopPlaybackService();
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
        }
        if (chat != null) {
            ViewGroup parent = (ViewGroup) chat.getParent();
            if (parent != null) parent.removeView(chat);
            chat.destroy();
        }
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (customView != null) { chrome.onHideCustomView(); return; }
        // v2.0: after an in-player switch, Back returns to
        // the previous video instead of exiting the player.
        if (!backStack.isEmpty()) {
            String[] prev = backStack.remove(backStack.size() - 1);
            switchToRaw(prev[0], prev[1], prev[2], prev[3], false);
            return;
        }
        // v3.29: Back shrinks to PiP like YouTube instead of
        // exiting the player (the PiP window's own X closes
        // it). If PiP is off or entry fails, Back exits as
        // before.
        if (pipOn(this) && !inPip && tryEnterPip(false)) return;
        super.onBackPressed();
    }
}
