package io.github.wlmosv_png.tgautosign.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import org.json.JSONObject;

import io.github.wlmosv_png.tgautosign.R;
import io.github.wlmosv_png.tgautosign.store.Store;

public class SettingsActivity extends Activity {

    private EditText editKeywords, editRetry;
    private Switch switchNotify, switchAutoLearn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);

        editKeywords = findViewById(R.id.editKeywords);
        editRetry = findViewById(R.id.editRetry);
        switchNotify = findViewById(R.id.switchNotify);
        switchAutoLearn = findViewById(R.id.switchAutoLearn);

        JSONObject cfg = Store.loadConfig(this);
        editKeywords.setText(cfg.optString("keywords", "签到,打卡,checkin,claim,领取,签到领,/qd,/qiandao,/sign"));
        editRetry.setText(String.valueOf(cfg.optInt("retryLimit", 5)));
        switchNotify.setChecked(cfg.optBoolean("notify", true));
        switchAutoLearn.setChecked(cfg.optBoolean("autoLearn", true));

        Button save = findViewById(R.id.btnSave);
        save.setOnClickListener(v -> {
            try {
                JSONObject c = Store.loadConfig(this);
                c.put("keywords", editKeywords.getText().toString().trim());
                int rl = Integer.parseInt(editRetry.getText().toString().trim());
                if (rl < 0 || rl > 99) rl = 5;
                c.put("retryLimit", rl);
                c.put("notify", switchNotify.isChecked());
                c.put("autoLearn", switchAutoLearn.isChecked());
                Store.saveConfig(this, c);
                Toast.makeText(this, "设置已保存（模块将在 30 分钟内轮询生效）", Toast.LENGTH_LONG).show();
                finish();
            } catch (Throwable e) {
                Toast.makeText(this, "重试上限需为数字", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
