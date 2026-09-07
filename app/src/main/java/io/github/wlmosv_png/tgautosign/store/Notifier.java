package io.github.wlmosv_png.tgautosign.store;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * TGAutoSign v2 通知（原生 API，无 AndroidX 依赖）
 */
public final class Notifier {

    public static final String CHANNEL_ID = "tg_autosign";
    private static final int BASE_ID = 1000;

    public static void ensureChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "自动签到", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("TGAutoSign 签到结果与提醒");
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {}
    }

    public static void notify(Context ctx, String title, String text, boolean enabled) {
        try {
            if (!enabled) return;
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setStyle(new Notification.BigTextStyle().bigText(text));
            nm.notify(BASE_ID + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (Throwable ignored) {}
    }
}
