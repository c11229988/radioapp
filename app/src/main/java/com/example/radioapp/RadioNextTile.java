package com.example.radioapp;

/** 下拉磁贴: 下一台 */
public class RadioNextTile extends BaseRadioTile {
    @Override
    protected String getAction() {
        return RadioService.ACTION_NEXT;
    }
}
