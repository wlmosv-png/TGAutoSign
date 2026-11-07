package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class UserConfig extends BaseController {
    public TLRPC.User currentUser;
    public long clientUserId;
    public static UserConfig getInstance(int n) { return null; }
    public static boolean isValidAccount(int n) { return true; }
}
