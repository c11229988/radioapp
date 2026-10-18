package com.example.radioapp;

/** 下拉磁贴: 上一台 */
public class RadioPrevTile extends BaseRadioTile {
    @Override
    protected String getAction() {
        return RadioService.ACTION_PREV;
    }
}
