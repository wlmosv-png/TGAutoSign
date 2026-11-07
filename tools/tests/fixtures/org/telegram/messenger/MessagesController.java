package org.telegram.messenger;
import java.util.ArrayList;
import org.telegram.tgnet.TLRPC;
public class MessagesController extends BaseController {
    public java.util.concurrent.ConcurrentHashMap<String, Object> users = new java.util.concurrent.ConcurrentHashMap<String, Object>();
    public static MessagesController getInstance(int n) { return null; }
    public ArrayList getDialogs(int folder) { return null; }
    public TLRPC.User getUser(Long id) { return null; }
    public TLRPC.Chat getChat(Long id) { return null; }
    public boolean processUpdateArray(ArrayList a, ArrayList b, ArrayList c, boolean d, int e) { return false; }
}
