package io.github.wlmosv_png.tgautosign.update;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置更新检查（v1.2.1 新增）
 *  - 查 GitHub Releases latest，与本地 VERSION_NAME / VERSION_CODE 比较
 *  - 网络或接口失败一律静默（Result.networkError），绝不影响签到主流程
 *  - 下载写入系统「下载」目录：API 29+ 用 MediaStore（不需要存储权限），更低版本写应用外部文件
 *  - 安装交给用户点开的系统安装器：不申请 REQUEST_INSTALL_PACKAGES，不自作主张装包
 */
public final class UpdateChecker {

    public static final String MODULE_ID = "io.github.wlmosv_png.tgautosign";
    /** 必须与 app/build.gradle 的 versionCode / versionName 手工保持一致
     *  （AGP 8 默认不生成 BuildConfig，这里不依赖它）。 */
    public static final int VERSION_CODE = 105;
    public static final String VERSION_NAME = "1.2.3";

    /** 依次尝试：官方镜像仓库（release 资产带 APK）→ 源码仓库 */
    private static final String[][] REPOS = {
            {"Xposed-Modules-Repo", "io.github.wlmosv_png.tgautosign"},
            {"wlmosv-png", "TGAutoSign"}
    };
    private static final String UA = "TGAutoSign/" + VERSION_NAME + " (LSPosed module; wlmosv)";
    private static final String PREFS = "tg_autosign_update";
    private static final long COOLDOWN_MS = 12L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 20000;
    private static final Pattern CODE_PREFIX = Pattern.compile("^(\\d{2,6})-");

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TGAutoSignUpdate");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public static final class Result {
        public boolean networkError;
        public boolean newer;
        public String version = "";
        public int remoteCode;
        public String apkUrl;
        public String apkName = "";
        public long apkSize;
        public String notes = "";
        public String sourceRepo = "";
        public String message = "";
        public String savedPath;
        public Uri savedUri;

        public String summary(String currentVersion) {
            if (networkError) {
                return "检查失败（网络或接口限制），不影响自动签到。\n" + message + "\n\n可稍后在 /jmb → 🔄 检查更新 里重试。";
            }
            if (newer) {
                String size = apkSize > 0 ? String.format(Locale.US, " · %.1f MB", apkSize / 1048576.0) : "";
                String body = "发现新版本 v" + version + "（当前 v" + currentVersion + size + "）\n来源: " + sourceRepo;
                if (apkUrl == null) body += "\n\n该版本没有可直接下载的安装包，请到发布页获取。";
                if (notes != null && !notes.isEmpty()) {
                    String n = notes.length() > 400 ? notes.substring(0, 400) + "…" : notes;
                    body += "\n\n更新说明：\n" + n;
                }
                return body;
            }
            return "已是最新版本 v" + currentVersion + "\n来源: " + sourceRepo;
        }
    }

    public interface Callback {
        void onResult(Result r);
    }

    /** @param force true 时忽略 12 小时冷却（用户在 /jmb 里主动点）；false 为开机静默检查 */
    public static void checkAsync(Context ctx, boolean force, Handler main, Callback cb) {
        SharedPreferences sp = prefs(ctx);
        long last = sp == null ? 0L : sp.getLong("last_check", 0L);
        if (!force && System.currentTimeMillis() - last < COOLDOWN_MS) return;
        if (sp != null) sp.edit().putLong("last_check", System.currentTimeMillis()).apply();
        POOL.execute(() -> {
            Result r = doCheck();
            if (cb != null) main.post(() -> cb.onResult(r));
        });
    }

    public static void downloadAsync(Context ctx, String url, String name, Handler main, Callback cb) {
        POOL.execute(() -> {
            Result r = new Result();
            r.apkUrl = url;
            try {
                String dest = download(ctx, url, name, r);
                if (dest == null) {
                    r.networkError = true;
                    r.message = "下载或写入失败";
                } else {
                    r.savedPath = dest;
                }
            } catch (Throwable t) {
                r.networkError = true;
                r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            if (cb != null) main.post(() -> cb.onResult(r));
        });
    }

    /** 打开系统安装器（content:// 走 MediaStore 授权；低版本 file:// 需 FileProvider，已兜底 toast 路径）。 */
    public static boolean openSaved(Context ctx, Uri uri, String path) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (uri != null) {
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (path != null && Build.VERSION.SDK_INT < 29) {
                i.setDataAndType(Uri.fromFile(new File(path)), "application/vnd.android.package-archive");
            } else {
                return false;
            }
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.i("TGAutoSignModule", "open installer failed: " + t);
            return false;
        }
    }

    // ---------------- 检查 ----------------

    private static Result doCheck() {
        Result r = new Result();
        for (String[] repo : REPOS) {
            String api = "https://api.github.com/repos/" + repo[0] + "/" + repo[1] + "/releases/latest";
            try {
                String body = httpGet(api);
                if (body == null || body.isEmpty()) continue;
                JSONObject rel = new JSONObject(body);
                if (!rel.has("tag_name")) continue;
                String tag = rel.optString("tag_name", rel.optString("name", ""));
                String[] pv = parseTag(tag);
                r.version = pv[0];
                r.remoteCode = parseInt(pv[1], 0);
                r.sourceRepo = repo[0] + "/" + repo[1];
                r.notes = rel.optString("body", "");
                pickAsset(rel, r);
                r.newer = decideNewer(r);
                return r;
            } catch (Throwable t) {
                r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.i("TGAutoSignModule", "update check failed on " + repo[0] + "/" + repo[1] + ": " + t);
            }
        }
        r.networkError = true;
        if (r.message.isEmpty()) r.message = "所有候选仓库都不可达";
        return r;
    }

    /** 兼容 v1.2.0（纯版本号）/ 103-1.2.0（码-版本）/ v1.2.0-debug 三种 tag 写法 */
    static String[] parseTag(String tag) {
        String t = tag == null ? "" : tag.trim();
        String code = "";
        Matcher m = CODE_PREFIX.matcher(t);
        if (m.find()) {
            code = m.group(1);
            t = t.substring(m.end());
        }
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) t = t.substring(1);
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '-' || c == '_' || c == ' ' || c == '/') { cut = i; break; }
        }
        return new String[]{t.substring(0, cut), code};
    }

    private static boolean decideNewer(Result r) {
        if (r.version == null || r.version.isEmpty()) return false;
        if (r.remoteCode > 0) return r.remoteCode > VERSION_CODE;
        return compareVersion(r.version, VERSION_NAME) > 0;
    }

    static int compareVersion(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseInt(x[i].replaceAll("\\D.*$", ""), 0) : 0;
            int yi = i < y.length ? parseInt(y[i].replaceAll("\\D.*$", ""), 0) : 0;
            if (xi != yi) return xi < yi ? -1 : 1;
        }
        return 0;
    }

    private static void pickAsset(JSONObject rel, Result r) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return;
        List<JSONObject> apks = new ArrayList<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name", "").toLowerCase(Locale.US);
            if (n.endsWith(".apk") && !n.contains("mapping") && !n.contains("debug")) apks.add(a);
        }
        JSONObject best = null;
        for (JSONObject a : apks) {
            if (a.optString("name").contains(r.version)) { best = a; break; }
        }
        if (best == null && !apks.isEmpty()) best = apks.get(0);
        if (best == null) return;
        r.apkUrl = best.optString("browser_download_url", null);
        r.apkName = best.optString("name", "");
        r.apkSize = best.optLong("size", 0L);
    }

    // ---------------- 下载 ----------------

    private static String download(Context ctx, String url, String name, Result r) throws Exception {
        if (name == null || name.trim().isEmpty()) name = "TGAutoSign.apk";
        if (!name.toLowerCase(Locale.US).endsWith(".apk")) name = name + ".apk";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                return loc == null ? null : download(ctx, loc, name, r);
            }
            if (code < 200 || code >= 300) return null;
            long expect = c.getContentLengthLong();
            InputStream in = c.getInputStream();
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    if (expect > 0) v.put(MediaStore.Downloads.SIZE, expect);
                    ContentResolver cr = ctx.getContentResolver();
                    Uri dest = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (dest == null) return null;
                    OutputStream os = cr.openOutputStream(dest);
                    if (os == null) return null;
                    long written = copy(in, os);
                    r.savedUri = dest;
                    r.apkSize = written;
                    r.apkName = name;
                    return Environment.DIRECTORY_DOWNLOADS + "/" + name;
                }
                File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = ctx.getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, name);
                OutputStream os = new FileOutputStream(out);
                long written = copy(in, os);
                r.apkSize = written;
                r.apkName = name;
                return out.getAbsolutePath();
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        } finally {
            c.disconnect();
        }
    }

    private static long copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            out.flush();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
        return total;
    }

    // ---------------- 小工具 ----------------

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            try {
                while ((n = in.read(buf)) > 0 && total < 512 * 1024) { bo.write(buf, 0, n); total += n; }
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        try { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); } catch (Throwable t) { return null; }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }
}
