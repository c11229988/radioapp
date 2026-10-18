package com.example.radioapp;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * 下拉快捷磁贴的基类: 点击即向 RadioService 下发对应指令。
 * 即使 RadioService 还没启动, startForegroundService 也会把它拉起来并开始播放。
 */
public abstract class BaseRadioTile extends TileService {

    /** 子类返回要下发的指令: ACTION_PREV / ACTION_NEXT 等 */
    protected abstract String getAction();

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, RadioService.class);
        i.setAction(getAction());
        startRadioService(i);
    }

    /** 兼容 Android 7/7.1 (支持磁贴但无 startForegroundService) */
    protected void startRadioService(Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }
}
