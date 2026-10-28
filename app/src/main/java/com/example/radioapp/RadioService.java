package com.example.radioapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.List;

/**
 * 后台播放服务(播放内核: ExoPlayer / Media3):
 * - ExoPlayer 支持自定义缓冲窗口, 解决 MediaPlayer 开局"播几秒卡一下"的问题
 * - 音频焦点由 ExoPlayer 托管(handleAudioFocus=true): 别的 App 播放时自动让位, 结束后自动恢复
 * - 用 MediaSession + 前台通知实现通知栏/锁屏的上一台/暂停/下一台控制
 * - 通过广播把播放状态发给主界面
 *
 * 主界面通过 startService(带 action) 下发指令:
 *   PLAY / TOGGLE / NEXT / PREV / STOP / REQUEST_STATE
 */
public class RadioService extends Service {

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
    private static final int MAX_RETRY = 4;

    private ExoPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioManager.AudioPlaybackCallback playbackCallback;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Handler mainHandler;

    private int currentIndex = -1;
    private boolean isPlaying = false;
    private boolean isBuffering = false;
    private int retryCount = 0;
    private boolean pausedByFocusLoss = false; // 是否因被其他应用抢走声音而暂停
    private boolean userWantsPlay = false;     // 用户是否"想听"(断网恢复后据此自动续播)

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

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean playing) {
            isPlaying = playing;
            updatePlaybackState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            updateNotification();
            broadcastState();
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
                isBuffering = true;
                updateNotification();
            } else if (playbackState == Player.STATE_READY) {
                isBuffering = false;
                retryCount = 0;
                updateNotification();
            } else if (playbackState == Player.STATE_ENDED) {
                // 直播流一般不到这里; 若服务端主动断开则重连
                if (userWantsPlay && currentIndex >= 0) play(currentIndex);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.e("RadioService", "ExoPlayer error: " + error.getErrorCodeName());
            isPlaying = false;
            isBuffering = false;
            updateNotification();
            broadcastState();
            scheduleReconnect();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSession = new MediaSession(this, "RadioSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(sessionCallback);
        mediaSession.setActive(true);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        // 兜底: 部分国产 ROM 在别的媒体停止后不会回调 AUDIOFOCUS_GAIN,
        // 这里直接监听系统里是否还有别的媒体在播, 没有了就自动续播(API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            playbackCallback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    if (!pausedByFocusLoss || isPlaying) return;
                    boolean otherMediaActive = false;
                    for (AudioPlaybackConfiguration c : configs) {
                        if (isOtherMediaActive(c)) {
                            otherMediaActive = true;
                            break;
                        }
                    }
                    if (!otherMediaActive) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() { resumeInternal(); }
                        });
                    }
                }
            };
            audioManager.registerAudioPlaybackCallback(playbackCallback, null);
        }

        // 网络恢复监听: 断网/弱网停播后, 网络一恢复就自动续播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (userWantsPlay && currentIndex >= 0 && !isPlaying) {
                                retryCount = 0;
                                play(currentIndex);
                            }
                        }
                    });
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
        }
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
            updateNotification();
        } else if (ACTION_REQUEST_STATE.equals(action)) {
            broadcastState();
        }
        return START_STICKY;
    }

    /**
     * 创建 ExoPlayer: 关键在缓冲窗口可调(MediaPlayer 做不到)。
     * - 最小缓冲 15 秒 / 最大 30 秒: 网络抖动时仍有充足余量, 不会一卡就断
     * - 起播前先攒 2.5 秒: 避免"点开播几秒就停一下"
     * - 卡顿后重新起播需攒 5 秒: 恢复后更稳
     * - WAKE_MODE_NETWORK: 息屏保持 Wi-Fi 与 CPU 唤醒, 防缓冲中断
     */
    private void ensurePlayer() {
        if (player == null) {
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 30000, 2500, 5000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            player = new ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .build();
            androidx.media3.common.AudioAttributes attrs =
                    new androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build();
            // 第二个参数 true: 由 ExoPlayer 自动处理音频焦点(别的 App 播放时让位, 结束后恢复)
            player.setAudioAttributes(attrs, true);
            player.setHandleAudioBecomingNoisy(true);
            player.setWakeMode(C.WAKE_MODE_NETWORK);
            player.addListener(playerListener);
        }
    }

    private void play(int index) {
        retryCount = 0;
        isBuffering = false;
        pausedByFocusLoss = false;
        userWantsPlay = true;
        List<Station> list = Stations.list;
        if (list.isEmpty()) return;
        int idx = Math.max(0, Math.min(index, list.size() - 1));
        currentIndex = idx;
        Station s = list.get(idx);
        ensurePlayer();
        try {
            player.setMediaItem(MediaItem.fromUri(s.url));
            player.prepare();
            player.play();
            updatePlaybackState(PlaybackState.STATE_CONNECTING);
            updateNotification();
        } catch (Exception e) {
            Log.e("RadioService", "play failed", e);
        }
    }

    /** 断流后按退避策略重连: 800ms → 1.5s → 3s → 5s */
    private void scheduleReconnect() {
        if (currentIndex < 0 || retryCount >= MAX_RETRY || !userWantsPlay) return;
        retryCount++;
        long delay;
        switch (retryCount) {
            case 1: delay = 800; break;
            case 2: delay = 1500; break;
            case 3: delay = 3000; break;
            default: delay = 5000; break;
        }
        final int idx = currentIndex;
        Log.e("RadioService", "网络抖动, " + delay + "ms 后自动重连第 " + retryCount + " 次");
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentIndex == idx && userWantsPlay) play(idx);
            }
        }, delay);
    }

    private void toggle() {
        if (currentIndex < 0) { play(0); return; }
        if (player == null) { play(currentIndex); return; }
        if (player.isPlaying()) {
            player.pause();
            userWantsPlay = false;
        } else {
            player.play();
            userWantsPlay = true;
            pausedByFocusLoss = false;
        }
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
        if (player != null) { player.stop(); }
        isPlaying = false;
        isBuffering = false;
        pausedByFocusLoss = false;
        userWantsPlay = false;
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        stopForeground(true);
        broadcastState();
    }

    private void resumeInternal() {
        if (player != null && !isPlaying && userWantsPlay && currentIndex >= 0) {
            pausedByFocusLoss = false;
            player.play();
        }
    }

    /**
     * 判断某个播放配置是否为"其它应用正在播放的媒体"。
     * 部分编译环境的 android.jar 未暴露 AudioPlaybackConfiguration 的实例方法,
     * 直接写 c.isActive() 会编译失败, 故改为反射调用(真机上是公开 API)。
     */
    private static boolean isOtherMediaActive(AudioPlaybackConfiguration c) {
        try {
            Object active = c.getClass().getMethod("isActive").invoke(c);
            if (!(active instanceof Boolean) || !((Boolean) active)) return false;
            Object attrs = c.getClass().getMethod("getAudioAttributes").invoke(c);
            if (attrs instanceof AudioAttributes) {
                int usage = ((AudioAttributes) attrs).getUsage();
                return usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME;
            }
        } catch (Throwable t) {
            return true; // 取不到就保守认为还有别的媒体在播, 避免误恢复
        }
        return false;
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
                ? (isBuffering ? "缓冲中…" : (isPlaying ? "正在播放" : "已暂停"))
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
        if (connectivityManager != null && networkCallback != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        if (audioManager != null && playbackCallback != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        }
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
