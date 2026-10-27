package io.github.wlmosv_png.tgautosign;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 签到提示音的**播放**（2026-10-25）。只负责"响"，不判断"该不该响"（那是 SoundGate）。
 *
 * 设计取舍：
 *   · 默认用**系统通知音**（RingtoneManager）——零体积、跟随用户自己的习惯；
 *   · 备选两种 ToneGenerator 合成音，避免要求任何权限、也不改 res/ 资源表；
 *   · 全部走 STREAM_NOTIFICATION，**不动系统音量、不打断音乐**；
 *   · 任何一步失败都静默降级（先合成音，再放弃），绝不让音效影响签到本身。
 */
public final class SignSound {

    public static final int STYLE_SYSTEM = SoundGate.STYLE_SYSTEM;  // 系统通知音
    public static final int STYLE_CHIME  = SoundGate.STYLE_CHIME;   // 清脆双音
    public static final int STYLE_LOW    = SoundGate.STYLE_LOW;     // 低音短促
    /** 自研「玻璃完成音」（assets/sign_done.ogg，2026-10-27）。 */
    public static final int STYLE_GLASS  = SoundGate.STYLE_GLASS;

    /** 自研音的 asset 名。放在 assets/ 而不是 res/raw —— 不进 resources.arsc，
     *  出包时无需重编资源表，降低"音效没进包却不报错"的风险。 */
    private static final String ASSET = "sign_done.ogg";
    /** SoundPool 的播放音量（相对流音量；0.9 略留余量，避免与系统音叠加时过冲）。 */
    private static final float GLASS_VOL = 0.9f;

    private SignSound() {}

    /**
     * 播一次（正式签到音）。style 非法时按系统通知音处理。
     *
     * 降级链（2026-10-28 加入 MediaPlayer 兜底）：
     *   3 完成音 → 通知池 →（没静音但通知音量 0 时改走媒体池）→ MediaPlayer(通知流) → 系统合成音
     *   0/1/2 三档行为未变。
     * 全程非阻塞；手动试听请用 playPreview()（媒体流、不看静音）。
     */
    public static void play(Context ctx, int style) {
        if (ctx == null) return;
        try {
            if (style == STYLE_GLASS) {
                if (notificationVolumeZero(ctx) && !isSilent(ctx)) {
                    if (fire(ctx, true)) {
                        android.util.Log.i(TAG, "通知音量为 0 → 本次用媒体流播放自研音");
                        return;
                    }
                }
                if (fire(ctx, false)) return;
                if (playViaPlayer(ctx, AudioManager.STREAM_NOTIFICATION)) return;
                tone(ToneGenerator.TONE_PROP_ACK);
                return;
            }
            if (style == STYLE_CHIME) {
                if (tone(ToneGenerator.TONE_PROP_ACK)) return;
            } else if (style == STYLE_LOW) {
                if (tone(ToneGenerator.TONE_PROP_BEEP2)) return;
            } else {
                if (ringtone(ctx)) return;
            }
            if (tone(ToneGenerator.TONE_PROP_ACK)) return;   // 兜底
            ringtone(ctx);
        } catch (Throwable ignored) {}
    }

    private static boolean ringtone(Context ctx) {
        return ringtoneOn(ctx, AudioManager.STREAM_NOTIFICATION);
    }

    /** 指定流类型播系统通知音。 */
    private static boolean ringtoneOn(Context ctx, int stream) {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (u == null) return false;
            Ringtone r = RingtoneManager.getRingtone(ctx, u);
            if (r == null) return false;
            try { r.setStreamType(stream); } catch (Throwable ignored) {}
            r.play();
            return true;
        } catch (Throwable t) { return false; }
    }

    // ─────────────── 自研「玻璃完成音」的播放（双 SoundPool + MediaPlayer 兜底）───────────────
    //
    // 2026-10-27/28 两次实测后定稿：
    //   · 用户手机当时是「静音 + 通知音量 0」→ 通知流确实无声，这是系统状态不是 bug；
    //     所以**试听**改走媒体流（USAGE_MEDIA），保证按下去一定听得到；
    //   · 但实测发现 SoundPool 的 **asset 加载在某些 ROM 上会失败**（AssetFileDescriptor 被提前
    //     关闭/实现差异）→ 于是改成：先把 asset 落成 cache 文件，再按**文件路径**加载；
    //   · 仍失败则用 **MediaPlayer** 兜底（最宽容），最后才退到系统合成音。
    //   · 正式签到音仍走通知流（静音/勿扰由 SoundGate 拦），"没静音但通知音量 0"时回退媒体流。

    private static final String TAG = "TGAutoSignSound";
    // GLASS_VOL 定义见文件上方档位常量区

    /** 一个自研音池（通知流 / 媒体流各一个）。 */
    private static final class Pool {
        SoundPool sp;
        int sid;
        int stream;
        boolean loaded;
        boolean pending;
        String err;
    }
    private static final Pool NOTIF = new Pool();
    private static final Pool MEDIA = new Pool();

    /**
     * 把音频落到宿主 cache 目录（一次性）；失败返回 null。
     *
     * 2026-10-28 修正（实测 FileNotFoundException）：**不能用 ctx.getAssets()** ——
     * 模块是独立 APK，代码注入宿主进程后这个 Context 的 assets 是**宿主的**，
     * 模块自己的资源根本不在里面。改为解码内嵌的 base64（SignSoundData），
     * 完全绕开资源系统 —— 出包链怎么变都不会丢。
     */
    private static File ensureAssetFile(Context ctx) {
        try {
            File f = new File(ctx.getCacheDir(), ASSET);
            if (f.exists() && f.length() > 0) return f;
            byte[] data = android.util.Base64.decode(SignSoundData.OGG_B64,
                    android.util.Base64.DEFAULT);
            if (data == null || data.length == 0) {
                android.util.Log.w(TAG, "内嵌音频解码为空");
                return null;
            }
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(data);
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
            }
            if (f.exists() && f.length() == data.length) return f;
            android.util.Log.w(TAG, "音频落盘长度不符：" + f.length() + " vs " + data.length);
            return null;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "写音频缓存失败：" + t);
            return null;
        }
    }

    /** 加载自研音到指定池：**文件路径优先**（最稳），AFD 仅兜底。 */
    private static int loadAsset(Context ctx, SoundPool sp, Pool owner) {
        try {
            File f = ensureAssetFile(ctx);
            if (f != null) {
                int id = sp.load(f.getAbsolutePath(), 1);
                if (id != 0) return id;
                owner.err = "path load returned 0";
            } else {
                owner.err = "asset file null";
            }
        } catch (Throwable t) {
            owner.err = "file: " + t;
        }
        // 兜底：把音频放进**宿主**资源时这条路才有效（当前实现走内嵌 base64，必然失败，无害）
        try {
            AssetFileDescriptor afd = ctx.getAssets().openFd(ASSET);
            try {
                int id = sp.load(afd, 1);
                if (id != 0) { owner.err = null; return id; }
                owner.err = (owner.err == null ? "" : owner.err + " | ") + "afd load returned 0";
            } finally {
                try { afd.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            owner.err = (owner.err == null ? "" : owner.err + " | ") + "openFd: " + t;
        }
        return 0;
    }

    private static Pool ensurePool(Context ctx, boolean media) {
        final Pool p = media ? MEDIA : NOTIF;
        if (p.sp != null) return p.sid != 0 ? p : null;
        synchronized (SignSound.class) {
            if (p.sp != null) return p.sid != 0 ? p : null;
            SoundPool sp = null;
            try {
                sp = new SoundPool.Builder()
                        .setMaxStreams(1)   // 同一时刻最多一路：天然防"一轮多回复连续响"
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(media ? AudioAttributes.USAGE_MEDIA
                                                : AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .build();
                sp.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                    @Override public void onLoadComplete(SoundPool sp2, int sampleId, int status) {
                        try {
                            p.loaded = (status == 0);
                            if (!p.loaded) android.util.Log.w(TAG, "onLoadComplete 失败 status=" + status);
                            if (p.loaded && p.pending) { p.pending = false; playNow(p); }
                        } catch (Throwable ignored) {}
                    }
                });
                int id = loadAsset(ctx, sp, p);
                if (id == 0) {
                    android.util.Log.w(TAG, "SoundPool.load 失败（" + (media ? "media" : "notif")
                            + "）：" + p.err);
                    try { sp.release(); } catch (Throwable ignored) {}
                    return null;
                }
                p.sp = sp;
                p.sid = id;
                return p;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "ensurePool 异常（" + (media ? "media" : "notif") + "）：" + t);
                try { if (sp != null) sp.release(); } catch (Throwable ignored) {}
                return null;
            }
        }
    }

    /** 用某个池放一次自研音。返回 false = 加载失败（调用方继续降级）。 */
    private static boolean fire(Context ctx, boolean media) {
        try {
            Pool p = ensurePool(ctx, media);
            if (p == null || p.sid == 0) return false;
            if (!p.loaded) {
                // 首次触发时样本可能还在解码：不降级，登记补播（加载完成通常 <100ms）
                p.pending = true;
                return true;
            }
            playNow(p);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 立即播放；同一时刻只保留一路。 */
    private static void playNow(Pool p) {
        try {
            if (p == null || p.sp == null || p.sid == 0) return;
            try { if (p.stream != 0) p.sp.stop(p.stream); } catch (Throwable ignored) {}
            p.stream = p.sp.play(p.sid, GLASS_VOL, GLASS_VOL, 1, 0, 1.0f);
        } catch (Throwable ignored) {}
    }

    /**
     * MediaPlayer 兜底：播 cache 里的文件。比 SoundPool 宽容得多，
     * 用来兜住"某些 ROM 上 SoundPool 就是加载不了"的情况。
     */
    private static boolean playViaPlayer(Context ctx, int streamType) {
        try {
            final File f = ensureAssetFile(ctx);
            if (f == null) return false;
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(streamType == AudioManager.STREAM_MUSIC
                            ? AudioAttributes.USAGE_MEDIA
                            : AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    try { m.release(); } catch (Throwable ignored) {}
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    android.util.Log.w(TAG, "MediaPlayer 错误 what=" + what + " extra=" + extra);
                    try { m.release(); } catch (Throwable ignored) {}
                    return true;
                }
            });
            mp.prepare();
            mp.start();
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "MediaPlayer 兜底失败：" + t);
            return false;
        }
    }

    /**
     * **手动试听**：媒体流 + 不看静音/勿扰 —— 用户主动按的按钮，必须听得到。
     * 降级链：媒体 SoundPool → MediaPlayer → 系统合成音（媒体流）。
     */
    public static void playPreview(Context ctx, int style) {
        if (ctx == null) return;
        try {
            if (style == STYLE_GLASS) {
                if (fire(ctx, true)) return;
                if (playViaPlayer(ctx, AudioManager.STREAM_MUSIC)) return;
                toneOn(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_ACK);
                return;
            }
            if (style == STYLE_CHIME) { toneOn(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_ACK); return; }
            if (style == STYLE_LOW)   { toneOn(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_BEEP2); return; }
            ringtoneOn(ctx, AudioManager.STREAM_MUSIC);
        } catch (Throwable ignored) {}
    }

    /** 通知音量是否为 0（此时通知流会"响而无声"）。 */
    public static boolean notificationVolumeZero(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            return am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) <= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 给设置页的一句话状态提示；返回 null = 一切正常、不需要提示。
     * 用户实测反馈："点了试听没声音、签到也没声音" → 就是被静音与通知音量 0 卡住的。
     */
    public static String audioStateHint(Context ctx) {
        if (ctx == null) return null;
        try {
            if (isSilent(ctx)) {
                return "手机处于静音/勿扰：签到成功时不会响（点「试听」仍能听到音色）";
            }
            if (notificationVolumeZero(ctx)) {
                return "通知音量为 0：签到成功时不会响；请调高通知音量（「试听」走媒体音量）";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 预热：宿主启动时在后台线程加载一次，让"第一次真正要响"时样本已经就绪。
     * 非阻塞、失败静默（失败只影响自研音，会自动降级）。
     */
    public static void prewarm(final Context ctx) {
        if (ctx == null || NOTIF.sp != null) return;
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { ensurePool(ctx, false); } catch (Throwable ignored) {}
                }
            }, "tgas-sound-prewarm").start();
        } catch (Throwable ignored) {}
    }

    /**
     * 宿主进程结束前释放 native 资源。不调用也不会泄漏到系统层（进程退出即回收），
     * 但显式释放更干净；释放后再次 play() 会重新懒加载。
     */
    public static synchronized void release() {
        for (Pool p : new Pool[]{NOTIF, MEDIA}) {
            try { if (p.sp != null) p.sp.release(); } catch (Throwable ignored) {}
            p.sp = null;
            p.sid = 0;
            p.stream = 0;
            p.loaded = false;
            p.pending = false;
        }
    }

    private static boolean tone(final int type) {
        return toneOn(AudioManager.STREAM_NOTIFICATION, type);
    }

    /** 指定流类型播一个系统合成音（试听用 STREAM_MUSIC，正式用 STREAM_NOTIFICATION）。 */
    private static boolean toneOn(final int stream, final int type) {
        ToneGenerator tg = null;
        try {
            tg = new ToneGenerator(stream, 80);
            // 320ms 短音：只提示"签上了"，不做铃声
            tg.startTone(type, 320);
            final ToneGenerator rel = tg;
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        new Runnable() {
                            @Override public void run() {
                                try { rel.release(); } catch (Throwable ignored) {}
                            }
                        }, 900L);
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            try { if (tg != null) tg.release(); } catch (Throwable ignored) {}
            return false;
        }
    }

    /**
     * 现在"不该出声"吗？—— 静音/振动模式，或勿打扰非「全部允许」。
     *
     * 都走公开 API，不需要任何权限。异常一律按"可以出声"处理：
     * 宁可多响一次，也别因为读不到系统状态就把功能做哑。
     */
    public static boolean isSilent(Context ctx) {
        if (ctx == null) return false;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int mode = am.getRingerMode();
                if (mode == AudioManager.RINGER_MODE_SILENT
                        || mode == AudioManager.RINGER_MODE_VIBRATE) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int f = nm.getCurrentInterruptionFilter();
                if (f != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                        && f != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
