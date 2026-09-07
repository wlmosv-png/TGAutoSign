package io.github.wlmosv_png.tgautosign.store;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * TGAutoSign v2.2 广播桥：模块(Telegram 进程) ↔ UI(模块 App 进程) 跨 UID 通信。
 * 广播对跨 uid 无权限限制 —— 比 ContentProvider 可靠。
 *
 * 动作：
 *  HB        模块心跳（UI 判定注入）
 *  STATE_REQ UI→模块 请求全量状态（config/state/日志缓冲）
 *  STATE_RESP 模块→UI 状态响应
 *  LOG       模块日志行（实时）
 *  CMD       UI→模块 命令（manual_sign/delete_target/reset_target/edit_target）
 */
public final class Bridge {

    public static final String ACTION_HB = "io.github.wlmosv_png.tgautosign.HB";
    public static final String ACTION_STATE_REQ = "io.github.wlmosv_png.tgautosign.STATE_REQ";
    public static final String ACTION_STATE_RESP = "io.github.wlmosv_png.tgautosign.STATE_RESP";
    public static final String ACTION_LOG = "io.github.wlmosv_png.tgautosign.LOG";
    public static final String ACTION_CMD = "io.github.wlmosv_png.tgautosign.CMD";

    public static final String EXTRA_TS = "ts";
    public static final String EXTRA_PID = "pid";
    public static final String EXTRA_CFG = "cfg";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_LOGS = "logs";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_JSON = "json";

    /** 模块内存日志缓冲（最近 N 行），UI 请求时随 STATE_RESP 下发 */
    public static final int LOG_BUFFER_MAX = 300;
    public static final List<String> LOG_BUFFER = new ArrayList<>();

    public static void bufferLog(String line) {
        synchronized (LOG_BUFFER) {
            LOG_BUFFER.add(line);
            while (LOG_BUFFER.size() > LOG_BUFFER_MAX) LOG_BUFFER.remove(0);
        }
    }

    public static String bufferLogsText() {
        synchronized (LOG_BUFFER) {
            StringBuilder sb = new StringBuilder();
            for (String l : LOG_BUFFER) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    /** 模块侧：注册接收 STATE_REQ / CMD */
    public static void registerModuleReceiver(Context ctx, BroadcastReceiver receiver) {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Bridge.ACTION_STATE_REQ);
            f.addAction(Bridge.ACTION_CMD);
            ctx.registerReceiver(receiver, f);
        } catch (Throwable ignored) {}
    }

    /** 模块侧：注册 UI 需要列表（若在同一进程） */
    public static void registerUiReceiver(Context ctx, BroadcastReceiver receiver) {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Bridge.ACTION_HB);
            f.addAction(Bridge.ACTION_STATE_RESP);
            f.addAction(Bridge.ACTION_LOG);
            ctx.registerReceiver(receiver, f);
        } catch (Throwable ignored) {}
    }

    public static void send(Context ctx, String action) {
        try {
            ctx.sendBroadcast(new Intent(action).setPackage("io.github.wlmosv_png.tgautosign"));
        } catch (Throwable ignored) {}
    }

    public static void sendHb(Context ctx) {
        try {
            Intent it = new Intent(ACTION_HB).setPackage("io.github.wlmosv_png.tgautosign");
            it.putExtra(EXTRA_TS, System.currentTimeMillis());
            it.putExtra(EXTRA_PID, android.os.Process.myPid());
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {}
    }

    public static void sendLogLine(Context ctx, String line) {
        try {
            Intent it = new Intent(ACTION_LOG).setPackage("io.github.wlmosv_png.tgautosign");
            it.putExtra(EXTRA_LINE, line);
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {}
    }

    public static void sendStateResp(Context ctx, String cfg, String state) {
        try {
            Intent it = new Intent(ACTION_STATE_RESP).setPackage("io.github.wlmosv_png.tgautosign");
            it.putExtra(EXTRA_CFG, cfg == null ? "{}" : cfg);
            it.putExtra(EXTRA_STATE, state == null ? "{}" : state);
            it.putExtra(EXTRA_LOGS, bufferLogsText());
            it.putExtra(EXTRA_TS, System.currentTimeMillis());
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {}
    }

    public static void sendCmd(Context ctx, String cmd, String json) {
        try {
            Intent it = new Intent(ACTION_CMD).setPackage("io.github.wlmosv_png.tgautosign");
            it.putExtra(EXTRA_CMD, cmd);
            it.putExtra(EXTRA_JSON, json);
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {}
    }

    public static void sendStateReq(Context ctx) {
        send(ctx, ACTION_STATE_REQ);
    }
}
