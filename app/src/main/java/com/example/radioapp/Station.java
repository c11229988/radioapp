package com.example.radioapp;

/** 单个电台:显示名称和可直接播放的音频流地址 */
public class Station {
    public final String name;
    public final String url;

    public Station(String name, String url) {
        this.name = name;
        this.url = url;
    }
}
