package io.github.wlmosv_png.tgautosign.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import io.github.wlmosv_png.tgautosign.R;
import io.github.wlmosv_png.tgautosign.store.Bridge;
import io.github.wlmosv_png.tgautosign.store.Store;

public class LogActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView txtLog;
    private final StringBuilder buf = new StringBuilder();

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            try {
                if (txtLog != null) {
                    txtLog.setText(buf.toString());
                    txtLog.post(() -> scrollToBottom());
                }
            } catch (Throwable ignored) {}
            handler.postDelayed(this, 2000);
        }
    };

    private void scrollToBottom() {
        int len = txtLog.getLineCount();
        if (len > 0) txtLog.scrollTo(0, Integer.MAX_VALUE);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                String a = intent.getAction();
                if (Bridge.ACTION_LOG.equals(a)) {
                    String line = intent.getStringExtra(Bridge.EXTRA_LINE);
                    if (line != null) append(line);
                } else if (Bridge.ACTION_STATE_RESP.equals(a)) {
                    String logs = intent.getStringExtra(Bridge.EXTRA_LOGS);
                    if (logs != null && !logs.isEmpty()) {
                        synchronized (buf) {
                            buf.setLength(0);
                            buf.append(logs);
                        }
                        Store.saveUiCache(LogActivity.this, "logbuf", logs);
                    }
                }
            } catch (Throwable ignored) {}
        }
    };

    private void append(String line) {
        synchronized (buf) {
            buf.append(line).append('\n');
            if (buf.length() > 300_000) buf.delete(0, buf.length() / 2);
        }
        // 实时刷新 UI 线程
        runOnUiThread(() -> {
            if (txtLog != null) {
                txtLog.setText(buf.toString());
                scrollToBottom();
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_log);
        txtLog = findViewById(R.id.txtLog);
        txtLog.setMovementMethod(new ScrollingMovementMethod());
        // 先展示本地缓存，再请求模块全量缓冲
        String cached = Store.uiCache(this, "logbuf", "");
        if (!cached.isEmpty()) {
            synchronized (buf) { buf.append(cached); }
            txtLog.setText(buf.toString());
        }
        Button clear = findViewById(R.id.btnClear);
        clear.setOnClickListener(v -> {
            synchronized (buf) { buf.setLength(0); }
            Store.saveUiCache(this, "logbuf", "");
            txtLog.setText("");
            Toast.makeText(this, "显示已清空（模块日志缓冲需打开 App 后自动同步）", Toast.LENGTH_SHORT).show();
        });
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Bridge.ACTION_LOG);
            f.addAction(Bridge.ACTION_STATE_RESP);
            registerReceiver(receiver, f);
        } catch (Throwable ignored) {}
        Bridge.sendStateReq(this);
        handler.post(refresher);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
    }
}
