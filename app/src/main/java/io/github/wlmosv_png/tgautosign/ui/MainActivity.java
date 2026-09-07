package io.github.wlmosv_png.tgautosign.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.wlmosv_png.tgautosign.R;
import io.github.wlmosv_png.tgautosign.store.Bridge;
import io.github.wlmosv_png.tgautosign.store.Store;

public class MainActivity extends Activity implements AdapterView.OnItemClickListener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView txtInject, txtToday;
    private ListView list;
    private TargetAdapter adapter;

    // 模块状态快照（经广播桥获取）
    private JSONObject cfg = new JSONObject();
    private JSONObject state = new JSONObject();
    private volatile long lastHb = 0L;
    private long lastHbTs = 0L;

    private final BroadcastReceiver bridgeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                String a = intent.getAction();
                if (Bridge.ACTION_HB.equals(a)) {
                    lastHb = System.currentTimeMillis();
                    refresh();
                } else if (Bridge.ACTION_STATE_RESP.equals(a)) {
                    cfg = new JSONObject(intent.getStringExtra(Bridge.EXTRA_CFG));
                    state = new JSONObject(intent.getStringExtra(Bridge.EXTRA_STATE));
                    String logs = intent.getStringExtra(Bridge.EXTRA_LOGS);
                    if (logs != null) Store.saveUiCache(MainActivity.this, "logbuf", logs);
                    lastHb = System.currentTimeMillis();
                    refresh();
                }
            } catch (Throwable ignored) {}
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        txtInject = findViewById(R.id.txtInject);
        txtToday = findViewById(R.id.txtToday);
        list = findViewById(R.id.list);
        adapter = new TargetAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(this);
        txtInject.setOnClickListener(v -> showInjectGuide());

        findViewById(R.id.btnLog).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Bridge.ACTION_HB);
            f.addAction(Bridge.ACTION_STATE_RESP);
            registerReceiver(bridgeReceiver, f);
        } catch (Throwable ignored) {}
        Bridge.sendStateReq(this);   // 请求模块全量状态
        refresh();
        handler.postDelayed(this::refresh, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(bridgeReceiver); } catch (Throwable ignored) {}
    }

    private void refresh() {
        // 注入自检：收到过 Hz/状态响应 = 已注入
        boolean hb = (lastHb != 0L && System.currentTimeMillis() - lastHb < 5 * 60 * 1000)
                || (lastHbTs != 0L && System.currentTimeMillis() - lastHbTs < 5 * 60 * 1000);
        if (hb) {
            txtInject.setText("🟢 已注入 Telegram（广播在线）");
            txtInject.setTextColor(0xFF2E7D32);
        } else {
            txtInject.setText("🔴 未连接模块：确认已启用并勾选 Telegram 作用域后重启");
            txtInject.setTextColor(0xFFC62828);
        }

        JSONArray arr = cfg.optJSONArray("targets") != null ? cfg.optJSONArray("targets") : new JSONArray();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        List<JSONObject> items = new ArrayList<>();
        int signed = 0;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                long did = o.optLong("dialogId");
                String p = "acc" + o.optInt("account", 0) + "_" + did + "_";
                String status;
                if (o.optBoolean("callback", false)) {
                    status = "回调型";
                } else if (today.equals(state.optString(p + "last"))) {
                    status = "已签";
                    signed++;
                } else if (state.optInt(p + "retry", 0) >= 5) {
                    status = "已放弃";
                } else if (state.optLong(p + "retry_at", 0) > System.currentTimeMillis()) {
                    status = "退避中";
                } else if (state.optInt(p + "retry", 0) > 0) {
                    status = "重试中";
                } else {
                    status = "待签";
                }
                o.put("_status", status);
                items.add(o);
            } catch (Throwable ignored) {}
        }
        adapter.setData(items);
        txtToday.setText("今日已签：" + signed + " / " + items.size());
        // widget 摘要缓存
        Store.saveUiCache(this, "widget_summary", "今日已签 " + signed + " / " + items.size());
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(60, 20, 60, 0);
        EditText uid = new EditText(this);
        uid.setHint("机器人 ID（数字，如 8543453092）");
        uid.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText cmd = new EditText(this);
        cmd.setHint("签到指令，如：/qd 或 📅 签到");
        box.addView(uid);
        box.addView(cmd);
        new AlertDialog.Builder(this)
                .setTitle("手动添加签到目标")
                .setView(box)
                .setPositiveButton("添加", (d, w) -> {
                    try {
                        long did = Long.parseLong(uid.getText().toString().trim());
                        String t = cmd.getText().toString().trim();
                        if (t.isEmpty()) {
                            Toast.makeText(this, "指令不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 立即本地加入（用户立刻看到 bot id + 指令 + 状态）
                        JSONArray arr = cfg.optJSONArray("targets") != null ? cfg.optJSONArray("targets") : new JSONArray();
                        boolean exists = false;
                        for (int i = 0; i < arr.length(); i++) {
                            if (arr.getJSONObject(i).optLong("dialogId") == did) exists = true;
                        }
                        if (!exists) {
                            arr.put(new JSONObject().put("dialogId", did).put("text", t).put("account", 0).put("callback", false));
                            try { cfg.put("targets", arr); } catch (Throwable ignored) {}
                        }
                        // 通知模块：加入并立即签到
                        Bridge.sendCmd(this, "manual_sign", new JSONObject().put("dialogId", did).put("text", t).toString());
                        Toast.makeText(this, "✅ 已添加 " + did + " → " + t + "（已通知模块签到）", Toast.LENGTH_LONG).show();
                        refresh();
                    } catch (Throwable e) {
                        Toast.makeText(this, "UID 格式错误", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        JSONObject target = (JSONObject) parent.getAdapter().getItem(position);
        if (target == null) return;
        final long did = target.optLong("dialogId");
        String text = target.optString("text", "");
        String[] actions = {"🚀 立即签到：" + text, "✏️ 修改指令", "🔄 重置今日状态", "🗑️ 删除该目标"};
        new AlertDialog.Builder(this)
                .setTitle("机器人 " + did)
                .setItems(actions, (d, w) -> {
                    try {
                        switch (w) {
                            case 0:
                                Bridge.sendCmd(this, "manual_sign", new JSONObject().put("dialogId", did).put("text", text).toString());
                                Toast.makeText(this, "已命令模块立即签到 " + did, Toast.LENGTH_SHORT).show();
                                break;
                            case 1: {
                                EditText e = new EditText(this);
                                e.setText(text);
                                new AlertDialog.Builder(this)
                                        .setTitle("修改签到指令")
                                        .setView(e)
                                        .setPositiveButton("保存", (d2, w2) -> {
                                            String nt = e.getText().toString().trim();
                                            if (nt.isEmpty()) return;
                                            editLocal(did, nt);
                                            try {
                                                Bridge.sendCmd(this, "edit_target", new JSONObject().put("dialogId", did).put("text", nt).toString());
                                            } catch (Throwable te) {}
                                            Toast.makeText(this, "已更新指令为 " + nt, Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                            }
                            case 2:
                                Bridge.sendCmd(this, "reset_target", new JSONObject().put("dialogId", did).toString());
                                Toast.makeText(this, "已命令重置今日状态", Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                removeLocal(did);
                                Bridge.sendCmd(this, "delete_target", new JSONObject().put("dialogId", did).toString());
                                Toast.makeText(this, "已删除目标 " + did, Toast.LENGTH_SHORT).show();
                                refresh();
                                break;
                        }
                    } catch (Throwable ignored) {}
                })
                .show();
    }

    private void editLocal(long did, String nt) {
        JSONArray arr = cfg.optJSONArray("targets");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            try {
                if (arr.getJSONObject(i).optLong("dialogId") == did) {
                    arr.getJSONObject(i).put("text", nt);
                    break;
                }
            } catch (Throwable ignored) {}
        }
        try { cfg.put("targets", arr); } catch (Throwable ignored) {}
        refresh();
    }

    private void removeLocal(long did) {
        JSONArray arr = cfg.optJSONArray("targets");
        if (arr == null) return;
        JSONArray na = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            try {
                if (arr.getJSONObject(i).optLong("dialogId") != did) na.put(arr.getJSONObject(i));
            } catch (Throwable ignored) {}
        }
        try { cfg.put("targets", na); } catch (Throwable ignored) {}
    }

    private void showInjectGuide() {
        new AlertDialog.Builder(this)
                .setTitle("模块未连接 · 排查")
                .setMessage("1. 打开 Xposed 管理器 → 模块 → 确认「TGAutoSign」已启用\n" +
                        "2. 点击该模块 → 勾选作用域「Telegram」\n" +
                        "3. 完全关闭 Telegram 再重新打开\n\n" +
                        "提示：模块内置 scope=org.telegram.messenger，新框架会自动预勾选；旧版本需手动勾选一次。\n\n" +
                        "若同时开着同功能的 LSPilot 插件版，请先禁用它避免双实例冲突。")
                .setPositiveButton("知道了", null)
                .setNegativeButton("重新连接", (d, w) -> {
                    lastHb = lastHbTs = 0L;
                    Bridge.sendStateReq(this);
                    Toast.makeText(this, "已发送连接请求，等待模块响应…", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
