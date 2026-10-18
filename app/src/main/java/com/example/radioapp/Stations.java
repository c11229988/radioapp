package com.example.radioapp;

import java.util.Arrays;
import java.util.List;

/**
 * 电台列表(数据源)
 * ----------------------------------------------------------------
 * 想加台 / 改台:照着下面格式在 Arrays.asList(...) 里加一行即可
 *   new Station("显示名称", "音频流地址")
 * 顺序就是 APP 里"上一台 / 下一台"切换的顺序。
 * 下面的地址都来自蜻蜓 FM 的直播流(http 直接可播放)。
 */
public class Stations {
    public static final List<Station> list = Arrays.asList(
        new Station("江苏经典流行音乐", "http://lhttp.qingting.fm/live/4938/64k.mp3"),
        new Station("广东音乐之声", "http://lhttp.qingting.fm/live/1260/64k.mp3"),
        new Station("上海动感101", "http://lhttp.qingting.fm/live/274/64k.mp3"),
        new Station("怀旧好声音", "http://lhttp.qingting.fm/live/1223/64k.mp3"),
        new Station("哈尔滨音乐广播", "http://lhttp.qingting.fm/live/839/64k.mp3"),
        new Station("苏州音乐广播", "http://lhttp.qingting.fm/live/2803/64k.mp3"),
        new Station("959年代音乐怀旧好声音", "http://lhttp.qingting.fm/live/5021381/64k.mp3"),
        new Station("动听音乐台", "http://lhttp.qingting.fm/live/5022107/64k.mp3"),
        new Station("500首华语经典", "http://lhttp.qingting.fm/live/5022308/64k.mp3"),
        new Station("北京音乐广播", "http://lhttp.qingting.fm/live/332/64k.mp3"),
        new Station("954汽车音乐广播", "http://lhttp.qingting.fm/live/1936/64k.mp3"),
        new Station("深圳飞扬971", "http://lhttp.qingting.fm/live/1271/64k.mp3"),
        new Station("950广西音乐台", "http://lhttp.qingting.fm/live/4875/64k.mp3"),
        new Station("厦门音乐广播", "http://lhttp.qingting.fm/live/1739/64k.mp3"),
        new Station("欧美音乐88.7", "http://lhttp.qingting.fm/live/15318703/64k.mp3"),
        new Station("无锡音乐广播", "http://lhttp.qingting.fm/live/2779/64k.mp3"),
        new Station("四川城市之音", "http://lhttp.qingting.fm/live/1111/64k.mp3"),
        new Station("陕西音乐广播", "http://lhttp.qingting.fm/live/4873/64k.mp3"),
        new Station("崂山921", "http://lhttp.qingting.fm/live/20212426/64k.mp3"),
        new Station("长沙101.7城市之声", "http://lhttp.qingting.fm/live/4237/64k.mp3"),
        new Station("上海经典947", "http://lhttp.qingting.fm/live/267/64k.mp3"),
        new Station("湖北经典音乐广播", "http://lhttp.qingting.fm/live/1296/64k.mp3"),
        new Station("成都时代音乐怀旧好声音", "http://lhttp.qingting.fm/live/20211686/64k.mp3"),
        new Station("山东经典音乐广播", "http://lhttp.qingting.fm/live/20240/64k.mp3"),
        new Station("江苏音乐广播Play897", "http://lhttp.qingting.fm/live/4936/64k.mp3"),
        new Station("天津TIKI 100.5", "http://lhttp.qingting.fm/live/20003/64k.mp3"),
        new Station("杭州90.7", "http://lhttp.qingting.fm/live/15318146/64k.mp3"),
        new Station("长春广播电视台 88.0", "http://lhttp.qingting.fm/live/4850/64k.mp3"),
        new Station("星河音乐", "http://lhttp.qingting.fm/live/20210755/64k.mp3"),
        new Station("江西音乐广播", "http://lhttp.qingting.fm/live/1802/64k.mp3"),
        new Station("广东广播电视台珠江之声", "http://lhttp.qingting.fm/live/470/64k.mp3"),
        new Station("内蒙古音乐之声", "http://lhttp.qingting.fm/live/1886/64k.mp3")
    );
}
