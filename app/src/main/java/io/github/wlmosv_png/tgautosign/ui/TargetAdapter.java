package io.github.wlmosv_png.tgautosign.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.tgautosign.R;

public class TargetAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<JSONObject> items = new ArrayList<>();

    public TargetAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setData(List<JSONObject> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int pos) {
        return items.get(pos);
    }

    @Override
    public long getItemId(int pos) {
        return items.get(pos).optLong("dialogId");
    }

    @Override
    public View getView(int pos, View convert, ViewGroup parent) {
        if (convert == null) {
            convert = LayoutInflater.from(ctx).inflate(R.layout.item_target, parent, false);
        }
        JSONObject o = items.get(pos);
        TextView badge = convert.findViewById(R.id.txtBadge);
        TextView id = convert.findViewById(R.id.txtId);
        TextView cmd = convert.findViewById(R.id.txtCmd);
        TextView status = convert.findViewById(R.id.txtStatus);

        long did = o.optLong("dialogId");
        String text = o.optString("text", "");
        String st = o.optString("_status", "待签");

        badge.setText(did > 0 ? String.valueOf(Math.abs(did) % 100) : "?");
        id.setText("uid: " + did);
        cmd.setText(text.isEmpty() ? "(无指令)" : text);
        status.setText(st);
        int color;
        if ("已签".equals(st)) color = ctx.getResources().getColor(R.color.success);
        else if ("重试中".equals(st) || "退避中".equals(st)) color = ctx.getResources().getColor(R.color.warn);
        else if ("回调型".equals(st)) color = ctx.getResources().getColor(R.color.warn);
        else color = ctx.getResources().getColor(R.color.muted);
        status.setTextColor(color);
        return convert;
    }
}
