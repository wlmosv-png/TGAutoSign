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
    public static final int VERSION_CODE = 131;
    public static final String VERSION_NAME = "1.6.5";
    /** 本机测试包的补丁标记（正式发版时置空）。诊断包里会显示，
     *  用来区分"装了修复版"和"装了原始 1.5.7"——两者版本号相同。 */
    public static final String PATCH_TAG = "";

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
        public String apkSha256 = "";
        public String shaUrl = "";
        public long apkSize;
        public String notes = "";
        public String sourceRepo = "";
        public String message = "";
        public String savedPath;
        // 2026-10-06 P2：sha 未能校验（拉不到 sha256sum.txt 或缺同名行）。
        // 此时不静默放行，而是标记出来，由界面提示用户二次确认。
        public boolean shaUnverified;
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
public static void checkAsync(final Context ctx, boolean force, Handler main, final Callback cb) {
        final SharedPreferences sp = prefs(ctx);
        long last = sp == null ? 0L : sp.getLong("last_check", 0L);
        if (!force && System.currentTimeMillis() - last < COOLDOWN_MS) return;
        POOL.execute(() -> {
            Result r = doCheck();
            if (!r.networkError && sp != null) sp.edit().putLong("last_check", System.currentTimeMillis()).apply();
            if (cb != null) main.post(() -> cb.onResult(r));
        });
    }


public static void downloadAsync(Context ctx, String url, String name, Handler main, Callback cb) {
        downloadAsync(ctx, url, name, "", main, cb);
    }

    public static void downloadAsync(final Context ctx, String url, String name, final String expectSha, Handler main, final Callback cb) {
        POOL.execute(() -> {
            Result r = new Result();
            r.apkUrl = url;
            r.apkSha256 = expectSha;
            try {
                String dest = download(ctx, url, name, r);
                if (dest == null) {
                    r.networkError = true;
                    if (r.message == null || r.message.isEmpty()) r.message = "下载或写入失败";
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
                if (r.shaUrl != null && !r.shaUrl.isEmpty()) r.apkSha256 = fetchShaFor(r.shaUrl, r.apkName);
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
    // 2026-10-06 P2：实现搬到 UpdateLogic（纯函数、可单测），此处转发。
    static String[] parseTag(String tag) {
        return io.github.wlmosv_png.tgautosign.UpdateLogic.parseTag(tag);
    }

    private static boolean decideNewer(Result r) {
        if (r.version == null || r.version.isEmpty()) return false;
        if (r.remoteCode > 0) return r.remoteCode > VERSION_CODE;
        return compareVersion(r.version, VERSION_NAME) > 0;
    }

    // 同上：转发到 UpdateLogic。
    static int compareVersion(String a, String b) {
        return io.github.wlmosv_png.tgautosign.UpdateLogic.compareVersion(a, b);
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
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && a.optString("name", "").toLowerCase(Locale.US).contains("sha256")) { r.shaUrl = a.optString("browser_download_url", null); break; }
        }
    }

    // ---------------- 下载 ----------------

    /** 重定向最多允许跳数（2026-10-06 P2）。原实现无限递归。 */
    private static final int MAX_REDIRECTS = 5;

    /**
     * 解析重定向目标（2026-10-06 P2 抽成纯函数，便于单测）。
     *
     * @return 解析后的绝对 https URL；相对路径按 base 解析。
     *         非 https、空、或解析失败一律返回 null（视为失败，不降级到 http）。
     */
    static String resolveRedirect(String baseUrl, String location) {
        return io.github.wlmosv_png.tgautosign.UpdateLogic.resolveRedirect(baseUrl, location);
    }

    private static String download(Context ctx, String url, String name, Result r) throws Exception {
        final java.security.MessageDigest md = newDigest();
        if (name == null || name.trim().isEmpty()) name = "TGAutoSign.apk";
        if (!name.toLowerCase(Locale.US).endsWith(".apk")) name = name + ".apk";

        // 2026-10-06 P2：重定向改为**有界循环**（原实现递归无上限，可被无限跳转拖死）。
        // 只允许 https；相对 Location 按当前 URL 解析；超限或降级到 http 视为失败。
        String cur = url;
        int hops = 0;
        while (true) {
            HttpURLConnection c = (HttpURLConnection) new java.net.URL(cur).openConnection();
            try {
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(READ_TIMEOUT);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", UA);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String next = resolveRedirect(cur, c.getHeaderField("Location"));
                    if (next == null) {
                        r.message = "下载被重定向到非 https 地址或空地址，已放弃";
                        return null;
                    }
                    if (++hops > MAX_REDIRECTS) {
                        r.message = "下载重定向超过 " + MAX_REDIRECTS + " 次，已放弃";
                        return null;
                    }
                    cur = next;
                    continue;   // 关掉当前连接，跳下一跳
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
                        // 2026-10-06 P2：先标 IS_PENDING=1，校验通过后再置 0 ——
                        // 避免半截文件被相册/下载器当成完整文件暴露出去。
                        try { v.put(MediaStore.Downloads.IS_PENDING, 1); } catch (Throwable ignored) {}
                        ContentResolver cr = ctx.getContentResolver();
                        Uri dest = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (dest == null) return null;
                        OutputStream os = null;
                        try {
                            os = cr.openOutputStream(dest);
                            if (os == null) { deleteMedia(cr, dest); return null; }
                            long written = copy(in, os, md);
                            r.savedUri = dest;
                            r.apkSize = written;
                            r.apkName = name;
                            // 大小校验（P2 第 4 点）
                            if (expect > 0 && written != expect) {
                                r.message = "下载字节数与服务器声明不符（" + written + " vs " + expect + "），已删除";
                                deleteMedia(cr, dest);
                                r.savedUri = null;
                                return null;
                            }
                            if (!shaOk(r, md)) { deleteMedia(cr, dest); r.savedUri = null; return null; }
                            // 校验都过了，取消 pending
                            ContentValues done = new ContentValues();
                            try { done.put(MediaStore.Downloads.IS_PENDING, 0); } catch (Throwable ignored) {}
                            try { cr.update(dest, done, null, null); } catch (Throwable ignored) {}
                            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
                        } catch (Throwable t) {
                            deleteMedia(cr, dest);   // 写入异常也要清理
                            r.savedUri = null;
                            throw t;
                        } finally {
                            try { if (os != null) os.close(); } catch (Throwable ignored) {}
                        }
                    }
                    File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = ctx.getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, name);
                    OutputStream os = null;
                    try {
                        os = new FileOutputStream(out);
                        long written = copy(in, os, md);
                        r.apkSize = written;
                        r.apkName = name;
                        if (expect > 0 && written != expect) {
                            r.message = "下载字节数与服务器声明不符（" + written + " vs " + expect + "），已删除";
                            try { out.delete(); } catch (Throwable ignored) {}
                            return null;
                        }
                        if (!shaOk(r, md)) { try { out.delete(); } catch (Throwable ignored) {} return null; }
                        return out.getAbsolutePath();
                    } catch (Throwable t) {
                        try { out.delete(); } catch (Throwable ignored) {}
                        throw t;
                    } finally {
                        try { if (os != null) os.close(); } catch (Throwable ignored) {}
                    }
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }
            } finally {
                c.disconnect();
            }
        }
    }

    /** 删除 MediaStore 记录（失败不抛）。 */
    private static void deleteMedia(ContentResolver cr, Uri dest) {
        try { cr.delete(dest, null, null); } catch (Throwable ignored) {}
    }

    private static long copy(InputStream in, OutputStream out, java.security.MessageDigest md) throws Exception {
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (md != null) md.update(buf, 0, n); total += n; }
            out.flush();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
        return total;
    }

    // ---------------- 小工具 ----------------

private static java.security.MessageDigest newDigest() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); } catch (Throwable t) { return null; }
    }

    private static String hexOf(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** 从 sha256sum.txt 里取该 APK 的校验值（没有匹配名字就退回第一条） */
    private static String fetchShaFor(String url, String apkName) {
        try {
            String text = httpGet(url);
            if (text == null) return "";
            // 2026-10-06 P2：**不再回退取第一条** ——
            // 一个 release 可能带多个 apk，取错会拿另一个包的 sha 去校验本包，
            // 结果是「明明下载正确却校验失败」。找不到同名行就返回空，
            // 由调用方走「未校验」路径让用户确认。
            for (String line : text.split("\n")) {
                String sha = io.github.wlmosv_png.tgautosign.UpdateLogic.parseShaLine(line, apkName);
                if (sha.length() == 64) return sha;
            }
            return "";
        } catch (Throwable t) { return ""; }
    }

    private static boolean shaOk(Result r, java.security.MessageDigest md) {
        String want = r.apkSha256 == null ? "" : r.apkSha256.trim().toLowerCase(Locale.US);
        // 2026-10-06 P2：拿不到有效 sha 时**不再静默放行**。
        // 选择「放行但标记 shaUnverified」而不是直接拒绝 —— 理由：
        //   sha256sum.txt 缺失时直接拒绝，用户就完全无法通过模块升级；
        //   而 sha 与 APK 同源，防不了发布端被篡改，真正的兜底是 Android
        //   对「同签名才能覆盖安装」的校验。所以标出来让用户二次确认更合理。
        if (want.length() != 64 || md == null) {
            r.shaUnverified = true;
            return true;
        }
        String got = hexOf(md.digest());
        if (want.equals(got)) return true;
        r.message = "安装包 sha256 与仓库里的 sha256sum.txt 不一致，已放弃：\n期望 " + want + "\n实际 " + got;
        Log.i("TGAutoSignModule", "sha256 mismatch " + want + " != " + got);
        return false;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) throw new java.io.IOException("HTTP " + code + "（可能是 API 限流或该发布不存在）");
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
