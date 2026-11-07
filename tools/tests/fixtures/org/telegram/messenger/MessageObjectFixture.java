package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
/** 夹具：结构上等价于 MessageObject（持有 TLRPC$Message 字段）。 */
public class MessageObjectFixture {
    public TLRPC.Message messageOwner;
}
