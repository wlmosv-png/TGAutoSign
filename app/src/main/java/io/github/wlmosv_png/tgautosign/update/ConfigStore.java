package io.github.wlmosv_png.tgautosign.update;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 配置导出 / 导入（v1.2.1 新增）—— 解决「换账号只能重新学一遍」。
 *
 * 覆盖两份偏好（都在 Telegram 进程内，模块有读写权限）：
 *   tg_autosign_gen  界面版主存储：acc{N}_learned_&lt;did&gt; / _last_ / _retry_ / _retry_at_、jmb_keywords、jmb_retry
 *   tg_autosign_v2   v2 存储层：cfg(targets/keywords/retryLimit/notify/autoLearn) / state / hb_ts
 * 导入采用「只合并、不清空」语义：文件里有的键覆盖，没有的键保留，避免手滑把新学目标抹掉。
 *
 * 落地位置：
 *   /sdcard/Android/data/org.telegram.messenger/files/tgautosign/TGAutoSign-config-&lt;时间&gt;.json （始终写这份）
 *   API 29+ 额外复制一份到系统「下载」目录，方便直接拷走
 */
public final class ConfigStore {

    public static final String FORMAT_KEY = "format";
    public static final int FORMAT_VERSION = 1;
    private static final String[] PREFS_LIST = {"tg_autosign_gen", "tg_autosign_v2"};
    private static final String SUB_DIR = "tgautosign";

    public static final class Report {
        public boolean ok;
        public String path;
        public Uri uri;
        public int prefFiles;
        public int keys;
        public String message = "";
    }

    // ---------------- 导出 ----------------

    public static Report exportAll(Context ctx) {
        Report rep = new Report();
        try {
            JSONObject root = new JSONObject();
            root.put(FORMAT_KEY, FORMAT_VERSION);
            root.put("module", UpdateChecker.MODULE_ID);
            root.put("moduleVersion", UpdateChecker.VERSION_NAME);
            root.put("moduleVersionCode", UpdateChecker.VERSION_CODE);
            root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            root.put("device", Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);

            JSONObject prefs = new JSONObject();
            int keys = 0;
            for (String name : PREFS_LIST) {
                SharedPreferences sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
                Map<String, ?> all = sp.getAll();
                if (all.isEmpty()) continue;
                JSONObject file = new JSONObject();
                for (String k : new TreeSet<>(all.keySet())) {
                    Object val = all.get(k);
                    JSONObject cell = new JSONObject();
                    if (val instanceof String) { cell.put("t", "s"); cell.put("v", val); }
                    else if (val instanceof Integer) { cell.put("t", "i"); cell.put("v", val); }
                    else if (val instanceof Long) { cell.put("t", "l"); cell.put("v", val); }
                    else if (val instanceof Boolean) { cell.put("t", "b"); cell.put("v", val); }
                    else if (val instanceof Float) { cell.put("t", "f"); cell.put("v", val); }
                    else { cell.put("t", "s"); cell.put("v", String.valueOf(val)); }
                    file.put(k, cell);
                    keys++;
                }
                prefs.put(name, file);
                rep.prefFiles++;
            }
            root.put("prefs", prefs);
            rep.keys = keys;
            if (keys == 0) {
                rep.message = "没有可导出的数据（还没有签到目标）";
                return rep;
            }

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String fileName = "TGAutoSign-config-" + UpdateChecker.VERSION_NAME + "-" + stamp + ".json";
            byte[] data = root.toString(2).getBytes("UTF-8");

            File dir = ctx.getExternalFilesDir(SUB_DIR);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, fileName);
                OutputStream os = new FileOutputStream(out);
                os.write(data);
                os.close();
                rep.path = out.getAbsolutePath();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.Downloads.SIZE, (long) data.length);
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) { os.write(data); os.close(); }
                    rep.uri = uri;
                    if (rep.path == null) rep.path = Environment.DIRECTORY_DOWNLOADS + "/" + fileName;
                }
            }
            rep.ok = rep.path != null;
            if (!rep.ok) rep.message = "写入导出文件失败";
            return rep;
        } catch (Throwable t) {
            rep.ok = false;
            rep.message = String.valueOf(t);
            return rep;
        }
    }

    // ---------------- 导入 ----------------

    /** @param fileOrNull null = 自动取 tgautosign 目录里最新的一份导出文件 */
    public static Report importAll(Context ctx, File fileOrNull) {
        Report rep = new Report();
        try {
            File f = fileOrNull != null ? fileOrNull : latestExportFile(ctx);
            if (f == null) {
                rep.message = "没找到导出文件（可放到 " + SUB_DIR + " 目录，或用系统「下载」里的 json）";
                return rep;
            }
            String text = read(f);
            rep.path = f.getAbsolutePath();
            return applyJson(ctx, text, rep);
        } catch (Throwable t) {
            rep.ok = false;
            rep.message = String.valueOf(t);
            return rep;
        }
    }

    /** 从公共「下载」目录导入（用户在另一台机器把 json 拷过来后用这个）。 */
    public static Report importFromDownloads(Context ctx, Uri uri) {
        Report rep = new Report();
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) { rep.message = "打不开该文件"; return rep; }
            rep.path = String.valueOf(uri);
            return applyJson(ctx, read(in), rep);
        } catch (Throwable t) {
            rep.ok = false;
            rep.message = String.valueOf(t);
            return rep;
        }
    }

    private static Report applyJson(Context ctx, String text, Report rep) throws Exception {
        JSONObject root = new JSONObject(text);
        int fmt = root.optInt(FORMAT_KEY, 0);
        if (fmt == 0 || fmt > FORMAT_VERSION) {
            rep.message = "配置文件格式不支持（format=" + fmt + "）";
            return rep;
        }
        JSONObject prefs = root.optJSONObject("prefs");
        if (prefs == null) { rep.message = "配置文件里没有 prefs 段"; return rep; }
        int n = 0;
        for (String name : PREFS_LIST) {
            JSONObject file = prefs.optJSONObject(name);
            if (file == null) continue;
            SharedPreferences sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            java.util.Iterator<String> it = file.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject cell = file.optJSONObject(k);
                if (cell == null) continue;
                String t = cell.optString("t", "s");
                Object v = cell.opt("v");
                if (v == null) continue;
                if ("s".equals(t)) ed.putString(k, String.valueOf(v));
                else if ("i".equals(t)) ed.putInt(k, cell.optInt("v"));
                else if ("l".equals(t)) ed.putLong(k, cell.optLong("v"));
                else if ("b".equals(t)) ed.putBoolean(k, cell.optBoolean("v"));
                else if ("f".equals(t)) ed.putFloat(k, (float) cell.optDouble("v"));
                else ed.putString(k, String.valueOf(v));
                n++;
            }
            ed.commit();
            rep.prefFiles++;
        }
        rep.keys = n;
        rep.ok = n > 0;
        if (!rep.ok) rep.message = "配置文件里没有可写入的键";
        return rep;
    }

    /** 导入前先看一眼文件里有什么（用于给用户确认）。 */
    public static String describe(File f) {
        try {
            JSONObject root = new JSONObject(read(f));
            JSONObject prefs = root.optJSONObject("prefs");
            int n = 0;
            if (prefs != null) {
                java.util.Iterator<String> it = prefs.keys();
                while (it.hasNext()) {
                    JSONObject file = prefs.optJSONObject(it.next());
                    n += file == null ? 0 : file.length();
                }
            }
            return root.optString("moduleVersion", "?") + " · " + n + " 项 · " + root.optString("exportedAt", "");
        } catch (Throwable t) {
            return "无法解析：" + t;
        }
    }

    public static File latestExportFile(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(SUB_DIR);
            if (dir == null || !dir.isDirectory()) return null;
            File[] fs = dir.listFiles((d, name) -> name.startsWith("TGAutoSign-config") && name.endsWith(".json"));
            if (fs == null || fs.length == 0) return null;
            File best = fs[0];
            for (File x : fs) if (x.lastModified() > best.lastModified()) best = x;
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String read(File f) throws Exception {
        return read(new FileInputStream(f));
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            char[] buf = new char[4096];
            int n;
            long total = 0;
            while ((n = br.read(buf)) > 0 && total < 8 * 1024 * 1024) { sb.append(buf, 0, n); total += n; }
        } finally {
            try { br.close(); } catch (Throwable ignored) {}
        }
        return sb.toString();
    }
}
