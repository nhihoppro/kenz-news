package com.rightnews.live;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.speech.tts.*;
import java.util.*;

// Nightly: "Nghe bài báo" — reads an article aloud with the
// phone's own free text-to-speech engine (no API key, no
// network cost). Picks the best installed voice for the
// article's language (Vietnamese when the article is
// translated, English for the original), preferring the
// highest-quality (neural) voice the device has, so the
// intonation is as natural as the free engine allows. Long
// articles are split into ~1000-char chunks at sentence
// boundaries and queued, because engines cap a single speak().
public class ArticleSpeaker {
    public interface Listener {
        void onStatus(String s);
        void onDone();
        void onError(String msg, boolean voiceMissing);
    }

    private static final int MAX_CHUNK = 1000;
    private final Activity act;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean initDone = false;
    private boolean starting = false;
    private String pendingText;
    private boolean pendingVietnamese;
    private int remaining = 0;
    private boolean speaking = false;

    public ArticleSpeaker(Activity act, Listener listener) {
        this.act = act;
        this.listener = listener;
    }

    public boolean isSpeaking() { return speaking; }

    public void speak(String text, boolean vietnamese) {
        if (text == null || text.trim().isEmpty()) {
            listener.onError("Không trích được chữ của bài này để đọc.", false);
            return;
        }
        pendingText = text;
        pendingVietnamese = vietnamese;
        if (initDone) {
            startSpeaking();
            return;
        }
        if (starting) return;
        starting = true;
        listener.onStatus("Đang chuẩn bị giọng đọc…");
        tts = new TextToSpeech(act, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                starting = false;
                if (status != TextToSpeech.SUCCESS) {
                    postError("Không khởi động được giọng đọc trên máy này.", false);
                    return;
                }
                initDone = true;
                startSpeaking();
            }
        });
    }

    private void startSpeaking() {
        if (tts == null || pendingText == null) return;
        Locale loc = pendingVietnamese ? new Locale("vi", "VN") : Locale.US;
        int langRes = TextToSpeech.LANG_NOT_SUPPORTED;
        try {
            // Best installed voice for this locale (highest quality
            // first — the neural voices sound the most natural).
            Voice best = null;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    if (v.getLocale() == null) continue;
                    String tag = v.getLocale().toLanguageTag();
                    if (!tag.equalsIgnoreCase(loc.toLanguageTag())) continue;
                    if (best == null || v.getQuality() > best.getQuality()) best = v;
                }
            }
            if (best != null) {
                if (tts.setVoice(best) == TextToSpeech.SUCCESS) langRes = TextToSpeech.LANG_AVAILABLE;
                else langRes = tts.setLanguage(loc);
            } else {
                langRes = tts.setLanguage(loc);
            }
        } catch (Exception e) {
            try { langRes = tts.setLanguage(loc); } catch (Exception ignored) {}
        }
        if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
            postError(pendingVietnamese
                    ? "Máy chưa có giọng tiếng Việt — cần tải giọng Việt trong Cài đặt giọng nói của máy rồi nghe lại."
                    : "Máy chưa có giọng tiếng Anh.", true);
            return;
        }
        tts.setSpeechRate(1.0f);
        tts.setPitch(1.0f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) {}
            public void onDone(String id) { onUtteranceDone(); }
            public void onError(String id) { onUtteranceDone(); }
            public void onError(String id, int code) { onUtteranceDone(); }
        });
        List<String> chunks = chunkText(pendingText, MAX_CHUNK);
        remaining = chunks.size();
        speaking = true;
        listener.onStatus("Đang đọc bài…");
        for (int i = 0; i < chunks.size(); i++) {
            Bundle params = new Bundle();
            tts.speak(chunks.get(i), TextToSpeech.QUEUE_ADD, params, "art-" + i);
        }
    }

    private void onUtteranceDone() {
        remaining--;
        if (remaining > 0) return;
        main.post(new Runnable() {
            public void run() {
                if (!speaking) return;
                speaking = false;
                listener.onDone();
            }
        });
    }

    public void stop() {
        speaking = false;
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
    }

    public void release() {
        stop();
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) {}
        tts = null;
        initDone = false;
    }

    private void postError(final String msg, final boolean voiceMissing) {
        main.post(new Runnable() {
            public void run() { listener.onError(msg, voiceMissing); }
        });
    }

    public static void openTtsSettings(Activity act) {
        try {
            act.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    // Split into sentences, then pack sentences into chunks no
    // longer than maxLen (a single over-long sentence is cut).
    private static List<String> chunkText(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split("(?<=[.!?])\\s+");
        StringBuilder cur = new StringBuilder();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            while (s.length() > maxLen) {
                if (cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(); }
                out.add(s.substring(0, maxLen));
                s = s.substring(maxLen).trim();
            }
            if (s.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + s.length() + 1 > maxLen) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur.length() > 0) cur.append(" ");
            cur.append(s);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
