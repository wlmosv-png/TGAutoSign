package io.github.wlmosv_png.tgautosign;

import io.github.wlmosv_png.tgautosign.host.DexReader;
import io.github.wlmosv_png.tgautosign.host.HostLocator;

/**
 * HostLocator 端到端单测 —— 拿**真实 APK** 跑，验证运行时自愈能把 9 个符号全找回来。
 *
 * <pre>
 *   java -cp &lt;cls&gt; io.github.wlmosv_png.tgautosign.HostLocateTest &lt;host.apk&gt;
 * </pre>
 *
 * 无 apk 参数时打印用法并以 0 退出（build.sh 在没提供宿主 APK 时会走这条）。
 */
public final class HostLocateTest {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("HostLocateTest: 用法 = HostLocateTest <host.apk>（未提供则跳过硬验证）");
            return;
        }
        long t0 = System.currentTimeMillis();
        DexReader r = DexReader.fromApk(args);
        if (r == null) {
            System.out.println("FAIL: DexReader=null（APK 读不到或不是有效 dex 容器）");
            System.exit(1);
        }
        long t1 = System.currentTimeMillis();
        HostLocator.Result res = HostLocator.locate(r);
        System.out.println("加载=" + (t1 - t0) + "ms 类数=" + r.classCount()
                + " 候选=" + res.candidates.size() + " 定位=" + res.scanMs + "ms");
        int ok = 0;
        for (String k : HostLocator.LEGACY) {
            String v = res.found.get(k);
            if (v != null) ok++;
            System.out.printf("  %-20s -> %s%n", k, v == null ? "✗" : v);
        }
        for (String n : res.notes) System.out.println("  note: " + n);
        System.out.println("定位成功 " + ok + "/" + HostLocator.LEGACY.length
                + "，总耗时 " + (System.currentTimeMillis() - t0) + "ms");
        System.exit(ok == HostLocator.LEGACY.length ? 0 : 1);
    }
}
