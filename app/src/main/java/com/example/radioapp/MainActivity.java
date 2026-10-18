package com.example.radioapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/**
 * 主界面:
 * - 上方:电台列表(点一下就开始播放该台)
 * - 下方(底部控制栏):上一台 / 播放暂停 / 下一台 三个按钮
 * - 实时显示当前正在播放的电台
 */
public class MainActivity extends Activity {

    private ListView listView;
    private TextView nowPlaying;
    private Button btnPrev;
    private Button btnPlay;
    private Button btnNext;
    private StationAdapter adapter;
    private int currentIndex = -1;
    private boolean isPlaying = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null) return;
            currentIndex = i.getIntExtra(RadioService.EXTRA_INDEX, -1);
            isPlaying = i.getBooleanExtra(RadioService.EXTRA_IS_PLAYING, false);
            refreshUI();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.station_list);
        nowPlaying = findViewById(R.id.now_playing);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);

        adapter = new StationAdapter(this, Stations.list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                sendControl(RadioService.ACTION_PLAY, position);
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendControl(RadioService.ACTION_PREV, null); }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendControl(RadioService.ACTION_NEXT, null); }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendControl(RadioService.ACTION_TOGGLE, null); }
        });

        IntentFilter filter = new IntentFilter(RadioService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 向服务询问当前状态(从通知栏回来时保持界面同步)
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_REQUEST_STATE);
        startService(i);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    private void refreshUI() {
        adapter.setCurrent(currentIndex);
        if (currentIndex >= 0) {
            nowPlaying.setText("正在播放:" + Stations.list.get(currentIndex).name);
        } else {
            nowPlaying.setText("未选择电台");
        }
        btnPlay.setText(isPlaying ? "⏸ 暂停" : "▶ 播放");
    }

    private void sendControl(String action, Integer index) {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(action);
        if (index != null) {
            i.putExtra(RadioService.EXTRA_INDEX, index);
        }
        if (RadioService.ACTION_STOP.equals(action)
                || RadioService.ACTION_REQUEST_STATE.equals(action)) {
            startService(i);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private static class StationAdapter extends BaseAdapter {
        private final Context ctx;
        private final List<Station> stations;
        private int current = -1;

        StationAdapter(Context ctx, List<Station> stations) {
            this.ctx = ctx;
            this.stations = stations;
        }

        void setCurrent(int i) {
            current = i;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return stations.size(); }

        @Override
        public Object getItem(int p) { return stations.get(p); }

        @Override
        public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(ctx)
                        .inflate(R.layout.list_item_station, parent, false);
            }
            TextView tv = v.findViewById(R.id.station_name);
            tv.setText(stations.get(p).name);
            v.setBackgroundColor(p == current
                    ? Color.parseColor("#E3F2FD")
                    : Color.parseColor("#FFFFFF"));
            return v;
        }
    }
}
