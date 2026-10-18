package com.example.radioapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 后台播放服务:
 * - 用 MediaPlayer 播放网络电台流
 * - 用 MediaSession + 前台通知实现"通知栏/锁屏"的上一台/暂停/下一台控制
 * - 通过广播把当前播放状态发给主界面(更新底部控制栏)
 *
 * 主界面通过 startService(带 action) 下发指令:
 *   PLAY / TOGGLE / NEXT / PREV / STOP / REQUEST_STATE
 */
public class RadioService extends Service
        implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener,
                MediaPlayer.OnInfoListener, MediaPlayer.OnBufferingUpdateListener {

    public static final String ACTION_PLAY = "com.example.radioapp.action.PLAY";
    public static final String ACTION_TOGGLE = "com.example.radioapp.action.TOGGLE";
    public static final String ACTION_NEXT = "com.example.radioapp.action.NEXT";
    public static final String ACTION_PREV = "com.example.radioapp.action.PREV";
    public static final String ACTION_STOP = "com.example.radioapp.action.STOP";
    public static final String ACTION_INIT = "com.example.radioapp.action.INIT";
    public static final String ACTION_REQUEST_STATE = "com.example.radioapp.action.REQUEST_STATE";
    public static final String ACTION_STATE = "com.example.radioapp.action.STATE";
    public static final String EXTRA_INDEX = "extra_index";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    public static final String EXTRA_STATION_NAME = "extra_station_name";

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "radio_channel";

    private MediaPlayer player;
    private MediaSession mediaSession;
    private int currentIndex = -1;
    private boolean isPlaying = false;
    private boolean isBuffering = false;
    private int retryCount = 0;
    private static final int MAX_RETRY = 4;

    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override
        public void onPlay() { toggle(); }

        @Override
        public void onPause() { toggle(); }

        @Override
        public void onSkipToNext() { next(); }

        @Override
        public void onSkipToPrevious() { previous(); }

        @Override
        public void onStop() { stop(); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSession = new MediaSession(this, "RadioSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(sessionCallback);
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PLAY.equals(action)) {
            play(intent.getIntExtra(EXTRA_INDEX, 0));
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREV.equals(action)) {
            previous();
        } else if (ACTION_STOP.equals(action)) {
            stop();
        } else if (ACTION_INIT.equals(action)) {
            // 打开 App 时让通知栏立刻出现控制按钮(即使还没选台)
            updateNotification();
        } else if (ACTION_REQUEST_STATE.equals(action)) {
            broadcastState();
        }
        return START_STICKY;
    }

    private void ensurePlayer() {
        if (player == null) {
            player = new MediaPlayer();
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            player.setAudioAttributes(attrs);
            player.setOnPreparedListener(this);
            player.setOnErrorListener(this);
            player.setOnInfoListener(this);
            player.setOnBufferingUpdateListener(this);
        }
    }

    private void play(int index) {
        retryCount = 0;
        isBuffering = false;
        List<Station> list = Stations.list;
        if (list.isEmpty()) return;
        int idx = Math.max(0, Math.min(index, list.size() - 1));
        currentIndex = idx;
        Station s = list.get(idx);
        ensurePlayer();
        try {
            player.reset();
            player.setDataSource(s.url);
            player.prepareAsync();
            updatePlaybackState(PlaybackState.STATE_CONNECTING);
            updateNotification();
        } catch (Exception e) {
            Log.e("RadioService", "setDataSource failed", e);
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        retryCount = 0;
        mp.start();
        isPlaying = true;
        isBuffering = false;
        updatePlaybackState(PlaybackState.STATE_PLAYING);
        updateNotification();
        broadcastState();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e("RadioService", "MediaPlayer error what=" + what + " extra=" + extra);
        isPlaying = false;
        isBuffering = false;
        updatePlaybackState(PlaybackState.STATE_ERROR);
        updateNotification();
        broadcastState();
        // 网络抖动导致断流: 自动重连当前电台(限制次数, 避免死循环)
        if (currentIndex >= 0 && retryCount < MAX_RETRY) {
            retryCount++;
            Log.e("RadioService", "网络抖动, 自动重连第 " + retryCount + " 次");
            final int idx = currentIndex;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (currentIndex == idx) play(idx);
                }
            }, 1500);
        }
        return true;
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        // 直播流网络波动时 MediaPlayer 会进入缓冲: 提示用户而非表现为"卡死"
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
            isBuffering = true;
            updatePlaybackState(PlaybackState.STATE_BUFFERING);
            updateNotification();
        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
            isBuffering = false;
            updatePlaybackState(PlaybackState.STATE_PLAYING);
            updateNotification();
        }
        return false;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        // 预留: 可用于显示缓冲进度
    }

    private void toggle() {
        if (currentIndex < 0) { play(0); return; }
        if (player == null) { play(currentIndex); return; }
        if (player.isPlaying()) {
            player.pause();
            isPlaying = false;
            updatePlaybackState(PlaybackState.STATE_PAUSED);
        } else {
            player.start();
            isPlaying = true;
            updatePlaybackState(PlaybackState.STATE_PLAYING);
        }
        updateNotification();
        broadcastState();
    }

    private void next() {
        if (currentIndex < 0) { play(0); return; }
        play((currentIndex + 1) % Stations.list.size());
    }

    private void previous() {
        if (currentIndex < 0) { play(0); return; }
        play((currentIndex - 1 + Stations.list.size()) % Stations.list.size());
    }

    private void stop() {
        if (player != null) { player.stop(); player.reset(); }
        isPlaying = false;
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        stopForeground(true);
        broadcastState();
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP;
        PlaybackState.Builder b = new PlaybackState.Builder();
        b.setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        b.setActions(actions);
        mediaSession.setPlaybackState(b.build());

        String name = currentIndex >= 0 ? Stations.list.get(currentIndex).name : "未选择电台";
        MediaMetadata.Builder m = new MediaMetadata.Builder();
        m.putString(MediaMetadata.METADATA_KEY_TITLE, name);
        m.putString(MediaMetadata.METADATA_KEY_ARTIST, "网络电台");
        mediaSession.setMetadata(m.build());
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "网络电台", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("网络电台播放通知");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }

        String title = currentIndex >= 0 ? Stations.list.get(currentIndex).name : "网络电台";
        String text = currentIndex >= 0
                ? (isBuffering ? "缓冲中…" : "正在播放")
                : "点击播放开始收听";

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlag());
        PendingIntent prevPi = makeActionIntent(ACTION_PREV, 1);
        PendingIntent togglePi = makeActionIntent(ACTION_TOGGLE, 2);
        PendingIntent nextPi = makeActionIntent(ACTION_NEXT, 3);

        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_player);
        views.setTextViewText(R.id.notif_title, title);
        views.setTextViewText(R.id.notif_text, text);
        views.setImageViewResource(R.id.notif_icon, R.drawable.ic_radio);
        views.setImageViewResource(R.id.notif_prev, R.drawable.ic_prev_white);
        views.setImageViewResource(R.id.notif_next, R.drawable.ic_next_white);
        views.setImageViewResource(R.id.notif_toggle,
                isPlaying ? R.drawable.ic_pause_white : R.drawable.ic_play_white);
        views.setOnClickPendingIntent(R.id.notif_prev, prevPi);
        views.setOnClickPendingIntent(R.id.notif_toggle, togglePi);
        views.setOnClickPendingIntent(R.id.notif_next, nextPi);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_radio)
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setColor(0xFF6C5CE7);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setCustomContentView(views);
            builder.setCustomBigContentView(views);
        } else {
            builder.setContent(views);
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        startForeground(NOTIF_ID, builder.build());
    }

    private PendingIntent makeActionIntent(String action, int reqCode) {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(action);
        return PendingIntent.getService(this, reqCode, i, pendingFlag());
    }

    private int pendingFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE;
        }
        return 0;
    }

    private void broadcastState() {
        Intent i = new Intent(ACTION_STATE);
        i.putExtra(EXTRA_INDEX, currentIndex);
        i.putExtra(EXTRA_IS_PLAYING, isPlaying);
        i.putExtra(EXTRA_STATION_NAME,
                currentIndex >= 0 ? Stations.list.get(currentIndex).name : "");
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        if (player != null) { player.release(); player = null; }
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
