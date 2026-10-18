package com.example.radioapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 下拉磁贴: 播放 / 暂停 (二合一)
 * - 图标与文字会随播放状态实时变化 (播放中显示"暂停", 暂停时显示"播放")
 * - 拉下状态栏时主动询问一次当前状态, 并监听 RadioService 的状态广播
 */
public class RadioPlayTile extends TileService {

    private boolean isPlaying = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null) return;
            isPlaying = i.getBooleanExtra(RadioService.EXTRA_IS_PLAYING, false);
            refreshTile();
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        IntentFilter f = new IntentFilter(RadioService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        // 主动询问一次当前状态, 避免磁贴图标与实际状态不一致
        startService(new Intent(this, RadioService.class)
                .setAction(RadioService.ACTION_REQUEST_STATE));
    }

    @Override
    public void onStopListening() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignore) {
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_TOGGLE);
        startRadioService(i);
    }

    private void refreshTile() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setIcon(Icon.createWithResource(this,
                isPlaying ? R.drawable.ic_pause : R.drawable.ic_play));
        t.setLabel(isPlaying ? "暂停" : "播放");
        t.setState(Tile.STATE_ACTIVE);
        t.updateTile();
    }
}
