package io.github.wlmosv_png.tgautosign.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.wlmosv_png.tgautosign.R;
import io.github.wlmosv_png.tgautosign.store.Store;

public class TGAutoSignWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
            rv.setTextViewText(R.id.widgetTitle, "TGAutoSign");
            rv.setTextViewText(R.id.widgetSummary, summary(ctx));
            Intent it = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            rv.setOnClickPendingIntent(R.id.widgetRoot, pi);
            mgr.updateAppWidget(id, rv);
        }
    }

    private String summary(Context ctx) {
        // 数据经 MainActivity 收到模块广播后缓存在 App 内部 prefs
        String s = Store.uiCache(ctx, "widget_summary", "");
        return s.isEmpty() ? "打开 App 获取状态" : s;
    }
}
