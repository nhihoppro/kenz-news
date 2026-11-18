package com.rightnews.live;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
// v3.29: the ONE background-playback service — background play
// and screen-off play are the same feature now, not two. The
// old build (v1.35) only kept the process alive with a plain
// notification and held NO wake lock, so turning the screen
// off let the CPU sleep and the WebView audio died at once
// (his 2026-11-17 report). This build fixes that three ways:
// (1) a PARTIAL_WAKE_LOCK held for the whole playback, so
// the CPU stays on with the screen off (manifest WAKE_LOCK);
// (2) it starts when the player OPENS, not in onStop, so
// there is no background-start race when the screen goes
// off or Home is pressed — it is already a foreground
// service by then; (3) a real MediaSession + MediaStyle
// notification, so the shade and the lock screen show a
// YouTube-like player (title, channel, art, play/pause,
// next, stop) instead of a bare "Đang phát" line. The
// service holds no media itself — the player activity's
// WebView keeps playing; the service guards the process,
// holds the wake lock, owns the session, and forwards
// control intents to the player (the player is in the same
// process, so a package-scoped broadcast reaches its
// dynamic receiver). START_NOT_STICKY: if the system kills
// it, it must not restart alone — the WebView it was
// guarding is gone with the player. Stops itself if the
// task is swiped away (onTaskRemoved), on the Stop action,
// or when the player dies (onDestroy -> stopService).
public class PlaybackService extends Service {
    public static final String EXTRA_CHANNEL = "channel";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PLAYING = "playing";
    public static final String ACTION_START = "com.rightnews.live.PLAYBACK_START";
    public static final String ACTION_UPDATE_STATE = "com.rightnews.live.PLAYBACK_UPDATE_STATE";
    public static final String ACTION_UPDATE_TITLE = "com.rightnews.live.PLAYBACK_UPDATE_TITLE";
    public static final String ACTION_TOGGLE = "com.rightnews.live.PLAYBACK_TOGGLE";
    public static final String ACTION_NEXT = "com.rightnews.live.PLAYBACK_NEXT";
    public static final String ACTION_STOP = "com.rightnews.live.PLAYBACK_STOP";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "playback";
    @Override public IBinder onBind(Intent intent) { return null; }
    private volatile boolean stopped = false;
    private boolean foregroundStarted = false;
    private PowerManager.WakeLock wakeLock;
    private MediaSession session;
    private String videoId, channel, title;
    private boolean playing = true;
    private Bitmap thumb;
    private int thumbGen = 0;
    @Override public void onCreate() {
        super.onCreate();
        // (1) the wake lock: without it the CPU sleeps the
        // moment the screen goes off and the WebView audio
        // stops, foreground service or not.
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KenzNews:playback");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
        // (3) the media session: the lock screen, the shade's
        // media controls and headset buttons all drive this
        // session; its callbacks forward to the player.
        try {
            session = new MediaSession(this, "KenzNewsPlayback");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { forwardToPlayer(LivePlayerActivity.ACTION_PLAY); }
                @Override public void onPause() { forwardToPlayer(LivePlayerActivity.ACTION_PAUSE); }
                @Override public void onSkipToNext() { forwardToPlayer(LivePlayerActivity.ACTION_NEXT); }
                @Override public void onStop() { forwardToPlayer(LivePlayerActivity.ACTION_STOP); stopSelf(); }
            });
            session.setActive(true);
        } catch (Exception ignored) {}
    }
    @Override public void onDestroy() {
        stopped = true;
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception ignored) {}
        session = null;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        super.onDestroy();
    }
    private void forwardToPlayer(String action) {
        try { sendBroadcast(new Intent(action).setPackage(getPackageName())); } catch (Exception ignored) {}
    }
    private PendingIntent servicePi(String action, int code) {
        Intent it = new Intent(this, PlaybackService.class);
        it.setAction(action);
        return PendingIntent.getService(this, code, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    // The notification is rebuilt on every state/title change
    // (and on start). startForeground on an already-foreground
    // service just updates the posted notification.
    private void refreshNotif() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL_ID, "Đang phát", NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTIF_ID, buildNotif());
            foregroundStarted = true;
            updateSession();
        } catch (Exception ignored) {
            stopSelf();
        }
    }
    private Notification buildNotif() {
        // Reopen: back to the existing player (singleTop +
        // CLEAR_TOP = no new instance, no playback restart).
        // The extras ride along so that, in the edge case
        // the player died first, a fresh instance can still
        // start from them instead of finishing on a null id.
        Intent reopen = new Intent(this, LivePlayerActivity.class);
        reopen.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        reopen.putExtra("videoId", videoId);
        reopen.putExtra("channel", channel);
        reopen.putExtra("title", title);
        final PendingIntent pi = PendingIntent.getActivity(this, 0, reopen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final String big = (title != null && !title.isEmpty()) ? title : "Đang phát";
        final String small = (channel != null && !channel.isEmpty()) ? channel : "Chạm để quay lại trình phát";
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(big)
                .setContentText(small)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,
                                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        playing ? "Tạm dừng" : "Phát", servicePi(ACTION_TOGGLE, 10)).build())
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                        "Tiếp theo", servicePi(ACTION_NEXT, 11)).build())
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Dừng", servicePi(ACTION_STOP, 12)).build())
                .setOngoing(true);
        if (thumb != null) b.setLargeIcon(thumb);
        if (session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return b.build();
    }
    // Keeps the session (lock screen / shade media controls)
    // in step with the notification: same title, channel,
    // art and play/pause state.
    private void updateSession() {
        if (session == null) return;
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title != null ? title : "")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, channel != null ? channel : "Kenz News")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Kenz News");
            if (thumb != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, thumb);
            session.setMetadata(mb.build());
            PlaybackState.Builder sb = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_STOP)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
            session.setPlaybackState(sb.build());
        } catch (Exception ignored) {}
    }
    // The video thumbnail for the lock-screen art (same
    // hqdefault the lists already use). Best-effort: a
    // failed fetch just leaves the notification without art.
    // Generation guard stops a stale fetch from clobbering
    // a newer video's art or resurrecting a stopped service.
    private void fetchThumb() {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}") || thumb != null) return;
        final String id = videoId;
        final int gen = ++thumbGen;
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    InputStream in = c.getInputStream();
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    try { in.close(); } catch (Exception ignored) {}
                    c.disconnect();
                    if (bmp == null || stopped || gen != thumbGen || !id.equals(videoId)) return;
                    thumb = bmp;
                    refreshNotif();
                } catch (Exception ignored) {}
            }
        }).start();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            // Notification actions hop through the service so
            // Stop still works even if the player is already
            // dead (the service stops itself); Toggle/Next
            // just forward to the player.
            if (ACTION_TOGGLE.equals(a) || ACTION_NEXT.equals(a)) {
                forwardToPlayer(ACTION_TOGGLE.equals(a) ? LivePlayerActivity.ACTION_TOGGLE : LivePlayerActivity.ACTION_NEXT);
                if (!foregroundStarted) stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_STOP.equals(a)) {
                forwardToPlayer(LivePlayerActivity.ACTION_STOP);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_UPDATE_STATE.equals(a)) {
                if (videoId == null) return START_NOT_STICKY;
                playing = intent.getBooleanExtra(EXTRA_PLAYING, true);
                refreshNotif();
                return START_NOT_STICKY;
            }
            if (ACTION_UPDATE_TITLE.equals(a)) {
                if (videoId == null) return START_NOT_STICKY;
                String v = intent.getStringExtra("videoId");
                String c = intent.getStringExtra(EXTRA_CHANNEL);
                String t = intent.getStringExtra(EXTRA_TITLE);
                if (v != null && !v.isEmpty() && !v.equals(videoId)) { videoId = v; thumb = null; fetchThumb(); }
                if (c != null) channel = c;
                if (t != null) title = t;
                refreshNotif();
                return START_NOT_STICKY;
            }
            // START (or a legacy no-action start): full info.
            String v = intent.getStringExtra("videoId");
            String c = intent.getStringExtra(EXTRA_CHANNEL);
            String t = intent.getStringExtra(EXTRA_TITLE);
            if (v != null && !v.isEmpty()) { if (!v.equals(videoId)) thumb = null; videoId = v; }
            if (c != null) channel = c;
            if (t != null) title = t;
            playing = true;
            refreshNotif();
            fetchThumb();
        } else {
            refreshNotif();
        }
        return START_NOT_STICKY;
    }
    @Override public void onTaskRemoved(Intent rootIntent) { stopSelf(); }
}
