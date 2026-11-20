package com.sijia.go;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/** 设置页: 目前只有「更新」一项(启动检查开关 + 手动检查), 后续设置继续往这里加 */
public class SettingsActivity extends Activity {

    private TextView status;
    private UpdateChecker checker;
    private boolean checking;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        UiTheme t = UiTheme.current(this);
        applyTheme(t);

        ((TextView) findViewById(R.id.tvSetVersion)).setText("v" + BuildConfig.VERSION_NAME);
        bindAutoRow(t);
        findViewById(R.id.btnSetBack).setOnClickListener(v -> finish());
        findViewById(R.id.rowSetCheck).setOnClickListener(v -> checkManual());
    }

    private void bindAutoRow(UiTheme t) {
        TextView value = findViewById(R.id.tvSetAuto);
        boolean on = UpdateChecker.autoEnabled(this);
        value.setText(on ? "开" : "关");
        value.setTextColor(on ? t.accent : t.subColor);
        findViewById(R.id.rowSetAuto).setOnClickListener(v -> {
            boolean next = !UpdateChecker.autoEnabled(this);
            UpdateChecker.setAutoEnabled(this, next);
            value.setText(next ? "开" : "关");
            value.setTextColor(next ? t.accent : t.subColor);
        });
    }

    private void applyTheme(UiTheme t) {
        getWindow().setStatusBarColor(t.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        findViewById(R.id.setRoot).setBackgroundColor(t.windowBg);

        android.graphics.Typeface serif =
                android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);
        TextView title = findViewById(R.id.tvSetTitle);
        title.setTextColor(t.titleColor);
        title.setTypeface(serif);
        title.setLetterSpacing(t.hairlineList ? 0.18f : 0.05f);

        ((TextView) findViewById(R.id.btnSetBack)).setTextColor(t.subColor);
        ((TextView) findViewById(R.id.tvSetSub)).setTextColor(t.subColor);
        ((TextView) findViewById(R.id.tvSetSub)).setLetterSpacing(0.12f);
        ((TextView) findViewById(R.id.tvSetShare)).setTextColor(t.footColor);

        int[] labels = {R.id.tvSetVersionLabel, R.id.tvSetAutoLabel, R.id.tvSetCheckLabel};
        for (int id : labels) ((TextView) findViewById(id)).setTextColor(t.titleColor);
        ((TextView) findViewById(R.id.tvSetVersion)).setTextColor(t.subColor);
        ((TextView) findViewById(R.id.tvSetCheckIcon)).setTextColor(t.subColor);

        TextView auto = findViewById(R.id.tvSetAuto);
        auto.setTypeface(serif);

        status = findViewById(R.id.tvSetStatus);
        status.setTextColor(t.subColor);

        int[] divs = {R.id.divSetTop, R.id.divSetA, R.id.divSetB, R.id.divSetC};
        for (int id : divs) findViewById(id).setBackgroundColor(t.dividerColor);
    }

    private void checkManual() {
        if (checking) {
            status.setText("正在检查更新，请稍候…");
            return;
        }
        checking = true;
        status.setText("正在检查更新…");
        if (checker != null) {
            checker.destroy();
            checker = null;
        }
        checker = UpdateChecker.start(this, BuildConfig.VERSION_NAME, (c, r) -> {
            checking = false;
            if (isFinishing() || isDestroyed()) {
                c.destroy();
                checker = null;
                return;
            }
            UpdatePrompt.showResult(this, c, r, text -> status.setText(text), true);
        });
        if (checker == null) {
            checking = false;
            status.setText("已有检查在进行中，请稍候。");
        }
    }

    @Override
    protected void onDestroy() {
        if (checker != null) {
            checker.destroy();
            checker = null;
        }
        super.onDestroy();
    }
}
