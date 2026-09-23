#!/bin/sh
# i18n 门禁回归自测：故意注入 6 类违规，确认检查器会失败，然后还原。
# 用法：sh i18n/selftest.sh
# 任何一类不再被拦 → 门禁失效，必须修检查器。
cd "$(dirname "$0")/.." || exit 1
C=app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java
BAK=/data/local/tmp/_i18n_selftest.bak

cp "$C" "$BAK"
fail=0

check_expect_fail() {
    desc="$1"
    out=$(python3 i18n/check_i18n.py 2>&1 | head -3)
    if printf '%s' "$out" | grep -q '❌'; then
        echo "  ✅ 已拦住：$desc"
    else
        echo "  ❌ 没拦住（门禁盲区）：$desc"
        fail=1
    fi
    cp "$BAK" "$C"
}

echo "=== 基线（应通过）==="
python3 i18n/check_i18n.py | tail -1
if python3 i18n/check_i18n.py | grep -q '❌'; then
    echo "  ❌ 基线就不通过，先修源码"; rm -f "$BAK"; exit 1
fi

echo
echo "=== 变异测试（每项都应被拦住）==="

sed -i 's|stT.setText(Lang.tf("下次签到 {0}", nx));|stT.setText("下次签到");|' "$C"
check_expect_fail "出口裸中文字面量"

sed -i 's|Lang.tf("没有匹配「{0}」的日志", logQuery)|"没有匹配「" + logQuery + "」的日志"|' "$C"
check_expect_fail "碎片拼接传入出口"

sed -i 's|return Lang.tf("{0}  ·  约 {1} 分钟后", tm, left);|return tm + "  ·  约 " + left + " 分钟后";|' "$C"
check_expect_fail "辅助 String 方法体内裸中文"

sed -i 's|jlog("使用: 在任意聊天输入 /jmb 打开管理界面");|jlog("使用: 在任意聊天输入 /jmb 打开管理界面"); String _t = Lang.tr("这是一个绝不存在的测试键");|' "$C"
check_expect_fail "Lang 用了字典没有的键"

sed -i 's|.setNegativeButton(Lang.tr("取消"), null)|.setNegativeButton("取消", null)|' "$C"
check_expect_fail "AlertDialog 按钮裸中文"

sed -i 's|sb.append(Lang.tf("✅ TGAutoSign 今日签到完成 {0}/{1}", done, total));|sb.append("✅ TGAutoSign 今日签到完成 ").append(done);|' "$C"
check_expect_fail "消息生成器体内裸中文（规则7）"

echo
echo "=== 还原后（应通过）==="
python3 i18n/check_i18n.py | tail -1
rm -f "$BAK"

if [ "$fail" = 0 ]; then
    echo
    echo "✅ i18n 门禁自测通过（6/6）"
else
    echo
    echo "❌ i18n 门禁自测失败 —— 检查器有盲区"
    exit 1
fi
