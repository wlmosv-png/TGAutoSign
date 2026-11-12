package io.github.wlmosv_png.tgautosign;

/**
 * 接收链的**纯逻辑闸口**（2026-11-08 · F8）。
 *
 * 为什么独立：接收链的判断全在 TGAutoSignCore 的实例私有方法里，依赖 Android Context，
 * 纯 JVM 无法构造 → 18 项验收测试根本跑不起来。这里把「这条 update 该不该处理、为什么丢」
 * 抽成无依赖纯函数，测试**真正执行**它们。
 *
 * ⚠️ 语义必须与 TGAutoSignCore.onUpdateProcessed 现有一致（逐条对齐，见行号注释）。
 *    本类只做判定与原因归因，不做任何状态读写。
 */
public final class InboundGate {

    private InboundGate() { }

    // ── 丢弃原因码（与 Diag 的 drop 计数同源）──
    public static final String ACCEPT                 = "accept";
    public static final String DROP_UPDATE_NULL       = "drop:update_null";
    public static final String DROP_U_NULL            = "drop:element_null";
    public static final String DROP_NOT_NEW_MESSAGE   = "drop:not_new_message";
    public static final String DROP_MSG_NULL          = "drop:msg_null";
    public static final String DROP_PEER_ZERO         = "drop:peer_zero";
    public static final String DROP_SELF_OUT          = "drop:self_out";
    public static final String DROP_SELF_FROM         = "drop:self_from";
    public static final String DROP_PRIVATE_MISMATCH  = "drop:private_mismatch";
    public static final String DROP_GROUP_NO_AWAIT    = "drop:group_no_awaiting";

    // ── update 类型 ──
    public static final int UPD_NONE          = 0;
    public static final int UPD_NEW_MESSAGE   = 1;
    public static final int UPD_NEW_CHANNEL   = 2;
    /** 编辑消息（2026-11-12 P0）：bot 常"先发图/占位、再把文字编辑成结果"，
     *  真机「流光画廊」就是这么回「今天已签到，当前 777 廊币。」的 ——
     *  旧实现只认 TL_updateNewMessage，编辑更新被当成 not_new_message 丢掉，
     *  模块以为 bot 没回复 → 超时判「未回复」。 */
    public static final int UPD_EDIT_MESSAGE  = 3;

    /**
     * update 类型识别。语义与 TGAutoSignCore:24380-24382 完全一致：
     * 类名 contains "TL_updateNewMessage" 或 "TL_updateNewChannelMessage"。
     * （13.2.1 的真实名形如 org.telegram.tgnet.tl.TL_update$TL_updateNewMessage）
     */
    public static int updateKind(String className) {
        if (className == null) return UPD_NONE;
        if (className.contains("TL_updateNewMessage")) return UPD_NEW_MESSAGE;
        if (className.contains("TL_updateNewChannelMessage")) return UPD_NEW_CHANNEL;
        // 编辑类：私聊 TL_updateEditMessage / 频道与群 TL_updateEditChannelMessage
        if (className.contains("TL_updateEditMessage")) return UPD_EDIT_MESSAGE;
        if (className.contains("TL_updateEditChannelMessage")) return UPD_EDIT_MESSAGE;
        return UPD_NONE;
    }

    /** 该类型是否被接收链处理。 */
    public static boolean isSupportedUpdate(String className) { return updateKind(className) != UPD_NONE; }

    /**
     * 私聊判定：peer 是私聊时，要求发送者就是会话本人。
     * 对应 TGAutoSignCore:24447-24456 的 else 分支（peerUid<=0 || fromUid != peerUid）。
     */
    public static boolean privateFromMatches(long peerUid, long fromUid) {
        return peerUid > 0L && fromUid == peerUid;
    }

    /** 群/频道判定：peer 为负。对应 TGAutoSignCore:24424 `boolean isGroupPeer = peerUid < 0;` */
    public static boolean isGroupPeer(long peerUid) { return peerUid < 0L; }

    /**
     * 入站前置闸口的**逐条归因**（顺序与源码一致，第一个命中即返回）。
     *
     * @param elementIsNull  循环元素为 null（24378）
     * @param updateKind     {@link #updateKind(String)} 的结果（24380-24382）
     * @param msgIsNull      u.message == null（24384）
     * @param peerUid        解析出的会话 id（24410 判 0）
     * @param msgOut         消息 out 字段为 true（24443）
     * @param selfId         账号自身 id（24444）
     * @param fromUid        发送者 id（24456）
     * @return ACCEPT 或某个 DROP_*
     */
    public static String classifyEarly(boolean elementIsNull, int updateKind, boolean msgIsNull,
                                       long peerUid, boolean msgOut, long selfId, long fromUid) {
        if (elementIsNull) return DROP_U_NULL;
        if (updateKind == UPD_NONE) return DROP_NOT_NEW_MESSAGE;
        if (msgIsNull) return DROP_MSG_NULL;
        if (peerUid == 0L) return DROP_PEER_ZERO;
        if (msgOut) return DROP_SELF_OUT;
        if (selfId > 0L && fromUid == selfId) return DROP_SELF_FROM;
        if (!isGroupPeer(peerUid) && !privateFromMatches(peerUid, fromUid)) return DROP_PRIVATE_MISMATCH;
        return ACCEPT;
    }

    /** 群聊短路：该会话没有任何「已发出、等结论」的目标 → 丢弃（24500-24511）。 */
    public static String classifyGroupShortCircuit(boolean group, boolean anyAwaiting) {
        if (group && !anyAwaiting) return DROP_GROUP_NO_AWAIT;
        return ACCEPT;
    }

    /** 是否属于"原因可解释的丢弃"（用于统计口径自检）。 */
    public static boolean isDrop(String verdict) {
        return verdict != null && verdict.startsWith("drop:");
    }

    /**
     * 锚点 B（事件层）的入参形状判定。
     * 对应 TGAutoSignCore.onPostNotification:24162-24167 —— args 必须 >=2，且 args[1] 是 List。
     */
    public static boolean anchorBArgsOk(int argCount, boolean secondIsList) {
        return argCount >= 2 && secondIsList;
    }

    /**
     * 锚点 B 单条消息的前置闸口（handleOneMessageObject:24183+）。
     * 注意与链 A 的**差异**（这是真实存在的不对称，必须保留）：
     *   链 A：私聊要求 fromUid == peerUid（fromUid 为 0 也拒绝）
     *   链 B：只在 fromUid != 0 且 != peerUid 时拒绝（fromUid 为 0 时**放行**）
     */
    public static String classifyAnchorB(boolean ownerNull, boolean out, long peerUid, long fromUid,
                                         boolean textEmpty, boolean isMedia) {
        if (ownerNull) return "drop:b_owner_null";
        if (out) return "drop:b_self_out";
        if (peerUid == 0L) return "drop:b_peer_zero";
        if (peerUid > 0L && fromUid != 0L && fromUid != peerUid) return "drop:b_private_mismatch";
        if (textEmpty && !isMedia) return "drop:b_no_text";
        return ACCEPT;
    }

    /** 在途窗口判定：与 SignLogic.sentPhase 的 SENT_STALE 互为镜像（供关联链归因用）。 */
    public static boolean inFlight(boolean sentToday, long sentAtMs, long nowMs, long ttlMs) {
        if (!sentToday) return false;
        if (sentAtMs <= 0L) return false;
        long age = nowMs - sentAtMs;
        return age >= 0L && age <= ttlMs;
    }
}
