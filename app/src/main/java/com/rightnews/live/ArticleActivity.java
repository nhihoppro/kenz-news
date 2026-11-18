package com.rightnews.live;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
public class ArticleActivity extends Activity {
    private WebView web;
    // v1.14: last WebView scroll position, for the hide-on-scroll bar.
    private int lastWebScrollY = 0;
    private String originalUrl, title;
    private boolean translated = false;
    // Nightly: "Nghe bài báo" — the phone's free TTS reads the
    // article aloud (Vietnamese when translated, English for
    // the original). Stops when the article is left.
    private ArticleSpeaker speaker;
    private boolean speaking = false;
    // v1.11 (suggestion 7): third-party hosts seen while this
    // page loaded, for the "Báo QC" report. Collected on the
    // WebView thread, so a synchronized set; the report button
    // copies it under the same lock.
    private final java.util.Set<String> thirdPartyHosts =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private String publisherBase = "";
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_article);
        Theme.preferHighRefreshRate(this);
        // v3.9: status bar + navigation bar follow the theme
        // (dark icons on the light background, white on dark).
        Theme.applyStatusBar(this);
        // v1.4: same status-bar fix as main — pad by the real inset.
        final View articleRoot = findViewById(R.id.articleRoot);
        articleRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
                return insets;
            }
        });
        originalUrl = getIntent().getStringExtra("url");
        try {
            android.net.Uri ou = android.net.Uri.parse(originalUrl);
            String h = ou.getHost() == null ? "" : ou.getHost().toLowerCase(java.util.Locale.US);
            publisherBase = h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception ignored) {}
        title = getIntent().getStringExtra("title");
        // v1.7: article screen follows Sáng / Tối too.
        articleRoot.setBackground(Theme.bgDrawable(this));
        // v1.14: no applyBar here — the bar's BlurView (below,
        // once the WebView exists) draws blur + tint + stroke,
        // exactly like the main header/menu bars.
        ((TextView) findViewById(R.id.txtArticleTitle)).setTextColor(Theme.headerText(this));
        ((TextView) findViewById(R.id.txtArticleStatus)).setTextColor(Theme.subText(this));
        ((TextView) findViewById(R.id.txtTranslated)).setTextColor(Theme.subText(this));
        // v1.13: header buttons are plain text on the bar, like
        // the main header's buttons — no chip pill inside the bar.
        int[] chips = {R.id.btnBack, R.id.btnTranslate, R.id.btnMore};
        for (int id : chips) {
            TextView b = findViewById(id);
            b.setBackgroundColor(0x00000000);
            b.setTextColor(Theme.headerText(this));
        }
        ((TextView) findViewById(R.id.txtArticleTitle)).setText(title == null ? "" : title);
        web = findViewById(R.id.webArticle);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = web.getSettings();
        s.setUserAgentString(TranslateUtil.chromeUa());
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // v3.1: reader text follows the Cỡ chữ setting.
        s.setTextZoom(Theme.textZoom(this));
        // v1.14: article bar blur + hide-on-scroll. The bar's
        // BlurView snapshots the page's top strip (the bar sits
        // directly above the page), so it looks frosted like the
        // menu and tracks the page as it scrolls. Scrolling down
        // hides the bar for full-screen reading; scrolling up
        // brings it back. The WebView expands/shrinks in its
        // LinearLayout weight slot, so the page never jumps and
        // nothing is ever hidden under the bar.
        final BlurView articleBlur = findViewById(R.id.articleBlur);
        final View articleBarView = findViewById(R.id.articleBar);
        if (articleBlur != null) articleBlur.setSource(web);
        web.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                if (articleBlur != null) articleBlur.requestBlur();
                if (articleBarView != null) {
                    if (y > lastWebScrollY + 8 && y > 150 && articleBarView.getVisibility() == View.VISIBLE) {
                        articleBarView.setVisibility(View.GONE);
                    } else if (y < lastWebScrollY - 8 && articleBarView.getVisibility() != View.VISIBLE) {
                        articleBarView.setVisibility(View.VISIBLE);
                        if (articleBlur != null) articleBlur.requestBlur();
                    }
                }
                lastWebScrollY = y;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                findViewById(R.id.txtArticleStatus).setVisibility(View.GONE);
                if (articleBlur != null) articleBlur.requestBlur();
            }
            // v1.5: if the translated page fails, fall back to the original
            // instead of leaving a blank/error page with the button stuck
            // on "Bản gốc". The old code had no error path at all.
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && translated && originalUrl != null) {
                    translated = false;
                    ((TextView) findViewById(R.id.btnTranslate)).setText("Dịch: VI");
                    ((TextView) findViewById(R.id.txtArticleStatus)).setText("Không dịch được trang này, đang hiện bản gốc.");
                    findViewById(R.id.txtArticleStatus).setVisibility(View.VISIBLE);
                    v.loadUrl(originalUrl);
                }
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse resp) {
                if (req.isForMainFrame() && translated && resp != null && resp.getStatusCode() >= 400 && originalUrl != null) {
                    translated = false;
                    ((TextView) findViewById(R.id.btnTranslate)).setText("Dịch: VI");
                    ((TextView) findViewById(R.id.txtArticleStatus)).setText("Không dịch được trang này, đang hiện bản gốc.");
                    findViewById(R.id.txtArticleStatus).setVisibility(View.VISIBLE);
                    v.loadUrl(originalUrl);
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // Stay in-app; links open inside this WebView.
            }
            // v1.6: block ad/tracker requests in the reader. Both
            // overloads are needed: the String one for old APIs, the
            // Request one for API 21+. v1.11: the check also covers
            // user-reported hosts, and every non-blocked
            // third-party host is recorded for a possible report.
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                if (shouldBlockOrRecord(url)) return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                return super.shouldInterceptRequest(v, url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                if (req != null && req.getUrl() != null && shouldBlockOrRecord(req.getUrl().toString()))
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                return super.shouldInterceptRequest(v, req);
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (web.canGoBack()) web.goBack(); else finish(); }
        });
        // v3.1: "Mở ngoài" + "Báo QC" live in the ⋮ overflow
        // menu now (the bar was too crowded for both as
        // permanent buttons). The two actions are unchanged.
        // Nightly: "Nghe bài báo" joins the same menu.
        findViewById(R.id.btnMore).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.widget.PopupMenu menu = new android.widget.PopupMenu(ArticleActivity.this, v);
                menu.getMenu().add(0, 1, 0, "Mở trong trình duyệt");
                menu.getMenu().add(0, 2, 0, "Báo QC");
                menu.getMenu().add(0, 3, 0, speaking ? "Dừng đọc" : "Nghe bài báo");
                menu.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(android.view.MenuItem item) {
                        if (item.getItemId() == 1) { openExternally(); return true; }
                        if (item.getItemId() == 2) { reportAds(); return true; }
                        if (item.getItemId() == 3) {
                            if (speaking) stopListening();
                            else startListening();
                            return true;
                        }
                        return false;
                    }
                });
                menu.show();
            }
        });
        findViewById(R.id.btnTranslate).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                translated = !translated;
                ((TextView) findViewById(R.id.btnTranslate)).setText(translated ? "Bản gốc" : "Dịch: VI");
                findViewById(R.id.txtArticleStatus).setVisibility(View.VISIBLE);
                ((TextView) findViewById(R.id.txtArticleStatus)).setText(translated ? "Đang dịch sang tiếng Việt…" : "Đang tải bản gốc…");
                if (translated) {
                    String t = TranslateUtil.pageUrl(originalUrl);
                    if (t != null) web.loadUrl(t);
                    else { Toast.makeText(ArticleActivity.this, "Không dịch được link này, giữ bản gốc.", Toast.LENGTH_SHORT).show(); translated = false; web.loadUrl(originalUrl); }
                } else web.loadUrl(originalUrl);
            }
        });
        // Translated title (best-effort, silent fallback to original).
        if (title != null && !title.isEmpty()) {
            TranslateUtil.toVietnamese(title, new TranslateUtil.Cb() {
                public void onResult(String vi) {
                    if (vi != null && !vi.equals(title)) {
                        TextView tv = findViewById(R.id.txtTranslated);
                        tv.setText("VI: " + vi);
                        tv.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
        if (originalUrl != null) web.loadUrl(originalUrl);
        else finish();
    }
    // Nightly: "Nghe bài báo". Extract the article text from
    // the page (the <article> element when the site has one,
    // else the whole body), then read it with the phone's free
    // TTS. The voice follows the page language: Vietnamese
    // when the article is translated, English for the original.
    private void startListening() {
        if (web == null) return;
        ((TextView) findViewById(R.id.txtArticleStatus)).setText("Đang lấy chữ của bài…");
        findViewById(R.id.txtArticleStatus).setVisibility(View.VISIBLE);
        web.evaluateJavascript("(function(){var a=document.querySelector('article')||document.querySelector('[role=main]');var t=a?a.innerText:document.body.innerText;return t||'';})()",
                new ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        String text = cleanArticleText(decodeJsString(v));
                        if (title != null && !title.isEmpty()) text = title + ". " + text;
                        if (text.trim().isEmpty()) {
                            ((TextView) findViewById(R.id.txtArticleStatus)).setText("Không trích được chữ của bài này để đọc.");
                            return;
                        }
                        if (speaker == null) speaker = new ArticleSpeaker(ArticleActivity.this, new ArticleSpeaker.Listener() {
                            public void onStatus(String s) {
                                ((TextView) findViewById(R.id.txtArticleStatus)).setText(s);
                                findViewById(R.id.txtArticleStatus).setVisibility(View.VISIBLE);
                            }
                            public void onDone() {
                                speaking = false;
                                ((TextView) findViewById(R.id.txtArticleStatus)).setText("Đã đọc xong.");
                            }
                            public void onError(final String msg, final boolean voiceMissing) {
                                speaking = false;
                                ((TextView) findViewById(R.id.txtArticleStatus)).setText(msg);
                                if (voiceMissing) {
                                    new android.app.AlertDialog.Builder(ArticleActivity.this)
                                            .setTitle("Chưa có giọng đọc")
                                            .setMessage(msg + "\n\nMở Cài đặt giọng nói của máy để tải giọng tiếng Việt (miễn phí) rồi nghe lại.")
                                            .setPositiveButton("Mở cài đặt", new android.content.DialogInterface.OnClickListener() {
                                                public void onClick(android.content.DialogInterface d, int which) { ArticleSpeaker.openTtsSettings(ArticleActivity.this); }
                                            })
                                            .setNegativeButton("Để sau", null)
                                            .show();
                                } else {
                                    Toast.makeText(ArticleActivity.this, msg, Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                        speaking = true;
                        speaker.speak(text, translated);
                    }
                });
    }

    private void stopListening() {
        speaking = false;
        if (speaker != null) speaker.stop();
        ((TextView) findViewById(R.id.txtArticleStatus)).setText("Đã dừng đọc.");
    }

    // evaluateJavascript returns a JSON-encoded string; decode
    // it back to plain text (empty when the page gave nothing).
    private static String decodeJsString(String v) {
        if (v == null || v.equals("null") || v.isEmpty()) return "";
        try {
            Object o = new org.json.JSONTokener(v).nextValue();
            return o instanceof String ? (String) o : "";
        } catch (Exception e) {
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
                return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\n", "\n");
            return v;
        }
    }

    // Collapse the extracted text to readable paragraphs and
    // cap it (very long pages would otherwise queue forever).
    private static String cleanArticleText(String text) {
        if (text == null) return "";
        String t = text.replaceAll("[ \\t\\u00a0]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").trim();
        if (t.length() > 20000) t = t.substring(0, 20000);
        return t;
    }

    @Override protected void onPause() {
        super.onPause();
        // Nightly: stop reading when the article is left (Home,
        // Back, a call) — it never talks over anything else.
        if (speaking) stopListening();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (speaker != null) speaker.release();
        speaker = null;
    }
    // v1.14: prefer Chrome itself when installed, so its
    // built-in Translate is one tap away on the page;
    // otherwise fall back to the chooser.
    private void openExternally() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl() == null ? originalUrl : web.getUrl()));
            try { getPackageManager().getPackageInfo("com.android.chrome", 0); i.setPackage("com.android.chrome"); }
            catch (Exception ignored) {}
            startActivity(i);
        }
        catch (Exception e) { Toast.makeText(ArticleActivity.this, "Không mở được trình duyệt ngoài", Toast.LENGTH_SHORT).show(); }
    }
    // v1.11: report a slipped-through ad. Records the
    // third-party hosts seen on this page (collected in
    // shouldInterceptRequest); AdBlocker then blocks them
    // from the next page load onward.
    private void reportAds() {
        java.util.Set<String> reported = AdBlocker.reportedHosts(ArticleActivity.this);
        int before = reported.size();
        java.util.Set<String> copy;
        synchronized (thirdPartyHosts) { copy = new java.util.HashSet<>(thirdPartyHosts); }
        for (String h : copy) {
            if (h == null || h.isEmpty()) continue;
            if (!publisherBase.isEmpty() && (h.equals(publisherBase) || h.endsWith("." + publisherBase))) continue;
            if (h.equals("translate.goog") || h.endsWith(".translate.goog")) continue;
            if (AdBlocker.isAd("https://" + h + "/")) continue; // already blocked, nothing to report
            reported.add(h);
        }
        getSharedPreferences("kenzelections", MODE_PRIVATE).edit()
                .putStringSet("ad_reported_hosts", reported).apply();
        int added = reported.size() - before;
        Toast.makeText(ArticleActivity.this,
                added > 0 ? "Đã ghi " + added + " tên miền bên thứ ba trên trang này — từ lần tải sau những tên miền này sẽ bị chặn. Nếu trang nào vỡ vì chặn nhầm, dùng Mở ngoài."
                          : "Trang này không có tên miền bên thứ ba mới để ghi.",
                Toast.LENGTH_LONG).show();
    }
    // Returns true when this request must be blocked (static
    // list or a user-reported host). Otherwise records the host
    // when it is a genuine third party, for "Báo QC".
    private boolean shouldBlockOrRecord(String url) {
        if (url == null) return false;
        if (AdBlocker.isAd(ArticleActivity.this, url)) return true;
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.US);
            if (host.isEmpty()) return false;
            if (!publisherBase.isEmpty() && (host.equals(publisherBase) || host.endsWith("." + publisherBase))) return false;
            if (host.equals("translate.goog") || host.endsWith(".translate.goog")) return false;
            if (AdBlocker.isAd(url)) return false; // static-blocked hosts are not "seen" hosts
            thirdPartyHosts.add(host);
        } catch (Exception ignored) {}
        return false;
    }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
