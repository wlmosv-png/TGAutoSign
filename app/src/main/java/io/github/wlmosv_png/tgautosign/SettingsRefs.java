package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;

/**
 * 设置面板的控件句柄容器（Refactor 1.6.1 · 步骤 6）。
 *
 * 为什么需要：
 *   showSettings 有 555 行，7 个分区 + 一个"保存"块，而保存块要读 12 个控件
 *   （tmSw/nfSw/alSw/loSw/gapEd/wv/mdMin/ex/okW/failW…）。
 *   直接把分区抽成方法，变量就传不过去 —— 用一个容器持有，各分区往里写、
 *   保存块从里读，方法签名保持干净。
 *
 * 只做数据搬运，不含逻辑（逻辑仍在 TGAutoSignCore，避免引入循环依赖）。
 */
public final class SettingsRefs {

    public Activity act;
    public LinearLayout box;

    // 开关
    public Switch timerSw;      // 定时签到
    public Switch missBackSw;   // 错过补签
    public Switch notifySw;     // 签到结果通知
    public Switch notifyFailSw; // 只通知失败
    public Switch autoLearnSw;  // 按钮学习
    public Switch autoLearnNetSw;       // 网络学习
    public Switch autoLearnNetCfmSw;    // 网络学习需确认
    public Switch autoLearnFilterSw;    // 关键词过滤
    public Switch looseSw;      // 宽松模式
    public Switch judgeSw;      // 自动判定成功/失败
    public Switch judgeCustomSw;// 使用自定义词

    // 输入
    public EditText gapEd;      // 错开间隔
    public EditText excludeEd;  // 排除规则
    public EditText okWordsEd;  // 附加成功词
    public EditText failWordsEd;// 附加失败词

    // 签到窗口（分区内用时间选择器改动，保存时读取）
    public int[] wStart;
    public int[] wEnd;

    // 可变值（分区内改动，保存时读取）
    public String window = "";          // 签到窗口
    public int missDeadlineMin = 23 * 60;
    public int themeMode = 0;

    public SettingsRefs(Activity a, LinearLayout b) { this.act = a; this.box = b; }
}
