package com.sijia.go;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.sijia.go.banqi.BanqiActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_SGF = 1001;
    private static final int REQ_OPEN_RECORD = 1002;
    private int gameKind = 0;      // 0 围棋 / 1 象棋 / 2 五子棋 / 3 暗棋
    private UiTheme theme;
    private UpdateChecker updateChecker;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        int savedGame = sp.getInt("game", 0);
        gameKind = (savedGame >= 0 && savedGame <= 3) ? savedGame : 0;
        // 版本号来自构建信息，改 build.gradle 即自动同步（原先在布局里写死 v1.1.0）
        ((TextView) findViewById(R.id.tvFoot)).setText("v" + BuildConfig.VERSION_NAME);
        applyTheme(UiTheme.current(this));

        findViewById(R.id.btnTheme).setOnClickListener(v -> {
            UiTheme t = UiTheme.toggle(this);
            applyTheme(t);
        });
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.segGo).setOnClickListener(v -> switchGame(0));
        findViewById(R.id.segXq).setOnClickListener(v -> switchGame(1));
        findViewById(R.id.segGmk).setOnClickListener(v -> switchGame(2));
        findViewById(R.id.segBq).setOnClickListener(v -> switchGame(3));
        findViewById(R.id.btnGnu).setOnClickListener(v -> {
            if (gameKind == 0) showNewGameDialog();
            else if (gameKind == 1) showXiangqiNewGameDialog();
            else if (gameKind == 2) showGomokuNewGameDialog();
            else startBanqi("ai");
        });
        findViewById(R.id.btnLocal).setOnClickListener(v -> {
            if (gameKind == 0) showGoLocalDialog();
            else if (gameKind == 1) showXiangqiLocalDialog();
            else if (gameKind == 2) showGomokuLocalDialog();
            else showBanqiLocalDialog();
        });
        findViewById(R.id.btnSgf).setOnClickListener(v -> {
            if (gameKind == 0) showSgfList();
            else if (gameKind == 1) importXiangqiRecord();
            else if (gameKind == 2) showGomokuRules();
            else showBanqiRules();
        });
        checkUpdateOnStart();
    }

    /** 启动静默检查更新: 只有真的发现新版本才弹小对话框, 检测失败/无新版完全不打扰 */
    private void checkUpdateOnStart() {
        if (!UpdateChecker.autoEnabled(this)) return;
        updateChecker = UpdateChecker.start(this, BuildConfig.VERSION_NAME, (c, r) -> {
            if (isFinishing() || isDestroyed()) {
                c.destroy();
                updateChecker = null;
                return;
            }
            UpdatePrompt.showResult(this, c, r, null, false);
        });
    }

    @Override
    protected void onDestroy() {
        if (updateChecker != null) {
            updateChecker.destroy();
            updateChecker = null;
        }
        super.onDestroy();
    }

    private void switchGame(int kind) {
        if (gameKind == kind) return;
        gameKind = kind;
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("game", kind).apply();
        applyTheme(UiTheme.current(this));
    }

    /** 把主题色应用到主界面所有控件(含两主题各自的标志性元素) */
    private void applyTheme(UiTheme t) {
        this.theme = t;
        getWindow().setStatusBarColor(t.windowBg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        findViewById(R.id.root).setBackgroundColor(t.windowBg);
        float d = getResources().getDisplayMetrics().density;

        android.graphics.Typeface face = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);
        TextView tvTitle = findViewById(R.id.tvTitle);
        tvTitle.setTextColor(t.titleColor);
        tvTitle.setTypeface(face);
        tvTitle.setLetterSpacing(t.id == UiTheme.MONUMENT ? 0.18f : 0.05f);
        ((TextView) findViewById(R.id.tvSub)).setTextColor(t.subColor);
        ((TextView) findViewById(R.id.tvSub)).setLetterSpacing(0.12f);
        ((TextView) findViewById(R.id.tvFoot)).setTextColor(t.footColor);
        ((TextView) findViewById(R.id.tvFootR)).setTextColor(t.footColor);
        TextView btnTheme = findViewById(R.id.btnTheme);
        btnTheme.setTextColor(t.subColor);
        btnTheme.setText("主题 · " + t.name());
        ((TextView) findViewById(R.id.btnSettings)).setTextColor(t.subColor);

        // 粉彩: 穹顶纸上建筑; 墨白: 题字眉线 + 朱红印章
        findViewById(R.id.domeGroup).setVisibility(t.id == UiTheme.MONUMENT ? View.VISIBLE : View.GONE);
        android.graphics.drawable.GradientDrawable dome = new android.graphics.drawable.GradientDrawable();
        float r = 36 * d;
        dome.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        dome.setColor(android.graphics.Color.parseColor("#B7CDB4"));
        findViewById(R.id.dome).setBackground(dome);
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
        base.setCornerRadius(3 * d);
        base.setColor(android.graphics.Color.parseColor("#D8C9B8"));
        findViewById(R.id.domeBase).setBackground(base);

        findViewById(R.id.rowOrnament).setVisibility(t.hairlineList ? View.VISIBLE : View.GONE);
        findViewById(R.id.ornLineL).setBackgroundColor(t.dividerColor);
        findViewById(R.id.ornLineR).setBackgroundColor(t.dividerColor);
        findViewById(R.id.ornDotL).setBackgroundColor(t.dividerColor);
        findViewById(R.id.ornDotR).setBackgroundColor(t.dividerColor);
        findViewById(R.id.ornSeal).setBackgroundColor(t.accent);
        TextView seal = findViewById(R.id.tvSeal);
        if (t.hairlineList) {
            seal.setVisibility(View.VISIBLE);
            android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
            sg.setColor(t.accent);
            sg.setCornerRadius(3 * d);
            seal.setBackground(sg);
        } else {
            seal.setVisibility(View.GONE);
        }

        int[] entries = {R.id.btnGnu, R.id.btnLocal, R.id.btnSgf};
        int[] prefixes = {R.id.tvP1, R.id.tvP2, R.id.tvP3};
        int[] titles = {R.id.tvT1, R.id.tvT2, R.id.tvT3};
        int[] descs = {R.id.tvD1, R.id.tvD2, R.id.tvD3};
        int[] icons = {R.id.tvI1, R.id.tvI2, R.id.tvI3};
        int[][] tier = {{0, 24}, {14, 14}, {24, 0}}; // 粉彩: 阶梯错落
        for (int i = 0; i < 3; i++) {
            View entry = findViewById(entries[i]);
            entry.setBackground(t.entryDrawable(i));
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) entry.getLayoutParams();
            if (t.hairlineList) {
                lp.bottomMargin = 0;
                lp.leftMargin = 0;
                lp.rightMargin = 0;
            } else {
                lp.bottomMargin = (int) (17 * d);
                lp.leftMargin = (int) (tier[i][0] * d);
                lp.rightMargin = (int) (tier[i][1] * d);
            }
            entry.setLayoutParams(lp);
            TextView p = findViewById(prefixes[i]);
            p.setText(t.prefixes[i]);
            p.setTextColor(t.prefixColor);
            p.setTypeface(face);
            TextView ti = findViewById(titles[i]);
            ti.setTextColor(t.entryTitle);
            ti.setTypeface(face);
            ((TextView) findViewById(descs[i])).setTextColor(t.entryDesc);
            // 右端指示: 墨白 › 发丝箭头; 粉彩 ●○ 双子
            TextView icon = findViewById(icons[i]);
            if (t.hairlineList) {
                icon.setText("›");
                icon.setTextColor(t.subColor);
                icon.setTextSize(22);
            } else {
                android.text.SpannableString ss = new android.text.SpannableString("●○");
                ss.setSpan(new android.text.style.ForegroundColorSpan(t.entryTitle), 0, 1, 0);
                ss.setSpan(new android.text.style.ForegroundColorSpan(t.prefixColor), 1, 2, 0);
                icon.setText(ss);
                icon.setTextSize(13);
            }
        }
        int divVis = t.hairlineList ? View.VISIBLE : View.GONE;
        findViewById(R.id.div1).setVisibility(divVis);
        findViewById(R.id.div2).setVisibility(divVis);
        findViewById(R.id.divFoot).setVisibility(divVis);
        findViewById(R.id.div1).setBackgroundColor(t.dividerColor);
        findViewById(R.id.div2).setBackgroundColor(t.dividerColor);
        findViewById(R.id.divFoot).setBackgroundColor(t.dividerColor);
        applyGameSection(t);
    }

    /** 围棋 / 象棋 分段切换 + 入口文案 */
    private void applyGameSection(UiTheme t) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);

        // 墨白: 标题区与分段切换之间留出大块空白; 粉彩卡片更高, 间距收小避免溢出
        android.widget.LinearLayout.LayoutParams segLp =
                (android.widget.LinearLayout.LayoutParams) findViewById(R.id.segGroup).getLayoutParams();
        segLp.topMargin = (int) ((t.hairlineList ? 71 : 18) * d);
        findViewById(R.id.segGroup).setLayoutParams(segLp);

        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.TRANSPARENT);
        box.setStroke((int) (1 * d), t.hairlineList ? t.dividerColor : t.dlgLine);
        box.setCornerRadius((t.hairlineList ? 2 : 22) * d);
        findViewById(R.id.segGroup).setBackground(box);

        int[] segIds = {R.id.segGo, R.id.segXq, R.id.segGmk, R.id.segBq};
        for (int i = 0; i < segIds.length; i++) {
            TextView seg = findViewById(segIds[i]);
            boolean active = gameKind == i;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? t.primaryBg : Color.TRANSPARENT);
            bg.setCornerRadius((t.hairlineList ? 2 : 19) * d);
            seg.setBackground(bg);
            seg.setTextColor(active ? t.primaryText : t.subColor);
            seg.setTypeface(serif);
            seg.setLetterSpacing(0.08f);
        }

        String[] titles;
        String[] descs;
        if (gameKind == 0) {
            titles = new String[]{"人机对弈", "双人对弈", "棋谱欣赏"};
            descs = new String[]{"GNU Go / Pachi / KataGo · 等级让子可调", "同机对局 · 局域网联机", "内置棋谱 · 支持导入 SGF"};
        } else if (gameKind == 1) {
            titles = new String[]{"人机对弈", "本地双人", "棋谱复盘"};
            descs = new String[]{"Fairy-Stockfish 棋力分级 · Pikafish 高手", "红黑双方同机对弈", "导入 PGN / FEN 逐步打谱"};
        } else if (gameKind == 2) {
            titles = new String[]{"人机对弈", "本地双人", "玩法说明"};
            descs = new String[]{"自研棋型 AI · 五档难度 · 可提示", "同机轮流 · 局域网联机", "无禁手 · 黑先 · 五连即胜"};
        } else {
            titles = new String[]{"人机对弈", "本地双人", "玩法说明"};
            descs = new String[]{"自研暗棋 AI · 五档难度", "同机轮流 · 局域网联机", "军衔 · 翻子 · 炮隔子吃"};
        }
        int[] titleIds = {R.id.tvT1, R.id.tvT2, R.id.tvT3};
        int[] descIds = {R.id.tvD1, R.id.tvD2, R.id.tvD3};
        for (int i = 0; i < 3; i++) {
            ((TextView) findViewById(titleIds[i])).setText(titles[i]);
            ((TextView) findViewById(descIds[i])).setText(descs[i]);
        }
        // 页脚引擎署名：五子棋 / 暗棋是自研 AI，没有第三方引擎，不能沿用围棋/象棋的署名
        ((TextView) findViewById(R.id.tvFootR)).setText(gameKind == 2
                ? "自研棋型 AI · 无禁手 freestyle"
                : gameKind == 3 ? "自研暗棋 AI · 翻翻棋规则"
                : "Powered by KataGo · Pachi · GNU Go · Pikafish");
    }

    /** 围棋双人: 同机 or 局域网 */
    private void showGoLocalDialog() {
        UiTheme t = UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "双人对弈")
                .setItems(new String[]{"同机对弈", "局域网联机"}, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(this, GameActivity.class);
                        i.putExtra(GameActivity.EXTRA_MODE, "local");
                        startActivity(i);
                    } else {
                        Intent i = new Intent(this, LanLobbyActivity.class);
                        i.putExtra(LanLobbyActivity.EXTRA_GAME, "go");
                        startActivity(i);
                    }
                })
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    /** 象棋双人：同机 or 局域网 */
    private void showXiangqiLocalDialog() {
        UiTheme t = UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "双人对弈")
                .setItems(new String[]{"同机对弈", "局域网联机"}, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(this, XiangqiActivity.class);
                        i.putExtra(XiangqiActivity.EXTRA_MODE, "local");
                        startActivity(i);
                    } else {
                        Intent i = new Intent(this, LanLobbyActivity.class);
                        i.putExtra(LanLobbyActivity.EXTRA_GAME, "xiangqi");
                        startActivity(i);
                    }
                })
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    /** 五子棋双人: 同机 or 局域网 */
    private void showGomokuLocalDialog() {
        UiTheme t = UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "双人对弈")
                .setItems(new String[]{"同机对弈", "局域网联机"}, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(this, GomokuActivity.class);
                        i.putExtra(GomokuActivity.EXTRA_MODE, "local");
                        startActivity(i);
                    } else {
                        Intent i = new Intent(this, LanLobbyActivity.class);
                        i.putExtra(LanLobbyActivity.EXTRA_GAME, "gomoku");
                        startActivity(i);
                    }
                })
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    /** 五子棋人机: 难度 1-5 + 执子 + AI 落点提示 */
    private void showGomokuNewGameDialog() {
        UiTheme t = UiTheme.current(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_gomoku, null);
        SeekBar sbLevel = view.findViewById(R.id.sbLevel);
        TextView tvLevel = view.findViewById(R.id.tvLevel);
        TextView tvLbLevel = view.findViewById(R.id.tvLbLevel);
        TextView tvLbColor = view.findViewById(R.id.tvLbColor);
        TextView tvLbHints = view.findViewById(R.id.tvLbHints);
        TextView tvHints = view.findViewById(R.id.tvHints);
        TextView tvLbRenju = view.findViewById(R.id.tvLbRenju);
        TextView tvRenju = view.findViewById(R.id.tvRenju);
        RadioButton rbBlack = view.findViewById(R.id.rbBlack);
        RadioButton rbWhite = view.findViewById(R.id.rbWhite);

        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);
        float d = getResources().getDisplayMetrics().density;
        TextView tvTitle = view.findViewById(R.id.tvDlgTitle);
        tvTitle.setTextColor(t.titleColor);
        tvTitle.setTypeface(serif);
        tvTitle.setLetterSpacing(0.08f);
        GradientDrawable mk = new GradientDrawable();
        mk.setColor(t.accent);
        if (t.hairlineList) mk.setCornerRadius(2 * d);
        else mk.setShape(GradientDrawable.OVAL);
        view.findViewById(R.id.tvDlgMark).setBackground(mk);
        view.findViewById(R.id.divDlg).setBackgroundColor(t.dlgLine);
        for (TextView lb : new TextView[]{tvLbLevel, tvLbColor, tvLbHints, tvLbRenju}) {
            lb.setTextColor(t.subColor);
            lb.setLetterSpacing(0.1f);
        }
        for (RadioButton rb : new RadioButton[]{rbBlack, rbWhite}) {
            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_checked}, t.radioDrawable(true));
            sld.addState(new int[]{-android.R.attr.state_checked}, t.radioDrawable(false));
            rb.setButtonDrawable(sld);
            rb.setTextColor(t.titleColor);
        }
        sbLevel.setProgressDrawable(t.seekTrack());
        sbLevel.setThumb(t.seekThumb());
        tvLevel.setTextColor(t.titleColor);
        tvLevel.setTypeface(serif);
        tvLevel.setText(GomokuAi.levelName(sbLevel.getProgress() + 1));
        android.widget.Button btnStart = view.findViewById(R.id.btnStart);
        styleDialogPrimaryButton(t, btnStart, d);

        boolean hintsOn = getSharedPreferences("ui", MODE_PRIVATE)
                .getBoolean("gomoku_hints", true);
        tvHints.setText(hintsOn ? "开" : "关");
        tvHints.setTextColor(hintsOn ? t.accent : t.subColor);
        tvHints.setTypeface(serif);
        tvHints.setOnClickListener(v -> {
            boolean next = !getSharedPreferences("ui", MODE_PRIVATE)
                    .getBoolean("gomoku_hints", true);
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putBoolean("gomoku_hints", next).apply();
            tvHints.setText(next ? "开" : "关");
            tvHints.setTextColor(next ? t.accent : t.subColor);
        });

        boolean renjuOn = getSharedPreferences("ui", MODE_PRIVATE)
                .getBoolean("gomoku_renju", false);
        tvRenju.setText(renjuOn ? "开" : "关");
        tvRenju.setTextColor(renjuOn ? t.accent : t.subColor);
        tvRenju.setTypeface(serif);
        tvRenju.setOnClickListener(v -> {
            boolean next = !getSharedPreferences("ui", MODE_PRIVATE)
                    .getBoolean("gomoku_renju", false);
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putBoolean("gomoku_renju", next).apply();
            tvRenju.setText(next ? "开" : "关");
            tvRenju.setTextColor(next ? t.accent : t.subColor);
        });

        sbLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                tvLevel.setText(GomokuAi.levelName(p + 1));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        AlertDialog dlg = new AlertDialog.Builder(this).setView(view).create();
        btnStart.setOnClickListener(v -> {
            dlg.dismiss();
            Intent i = new Intent(this, GomokuActivity.class);
            i.putExtra(GomokuActivity.EXTRA_MODE, "ai");
            i.putExtra(GomokuActivity.EXTRA_LEVEL, sbLevel.getProgress() + 1);
            i.putExtra(GomokuActivity.EXTRA_COLOR,
                    rbWhite.isChecked() ? GomokuGame.WHITE : GomokuGame.BLACK);
            i.putExtra(GomokuActivity.EXTRA_HINTS, getSharedPreferences("ui", MODE_PRIVATE)
                    .getBoolean("gomoku_hints", true));
            i.putExtra(GomokuActivity.EXTRA_RENJU, getSharedPreferences("ui", MODE_PRIVATE)
                    .getBoolean("gomoku_renju", false));
            startActivity(i);
        });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(t.dialogBackground());
    }

    private void showGomokuRules() {
        UiTheme t = UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "五子棋 · 玩法说明")
                .setMessage("19 路棋盘，黑先。\n"
                        + "横、竖、斜任意方向先连成五子（含五子以上）即胜。\n"
                        + "默认无禁手（freestyle），不设三三、四四、长连限制。\n"
                        + "可在开局对话框或对局页切换「黑棋禁手」，按连珠规则禁止黑棋长连、四四、三三；白棋无禁手，成五优先判胜。\n"
                        + "棋盘下满仍未分胜负为和棋。\n\n"
                        + "人机对弈提供五档难度，可开启 AI 落点提示；\n"
                        + "本地双人同机轮流；局域网联机由房主统一裁决。")
                .setPositiveButton("知道了", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    /** 暗棋：人机（默认 5 档）或同机双人。 */
    private void startBanqi(String mode) {
        Intent i = new Intent(this, BanqiActivity.class);
        i.putExtra(BanqiActivity.EXTRA_MODE, mode);
        if ("ai".equals(mode)) i.putExtra(BanqiActivity.EXTRA_LEVEL, 5);
        startActivity(i);
    }

    /** 暗棋双人: 同机 or 局域网 */
    private void showBanqiLocalDialog() {
        UiTheme t = UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "双人对弈")
                .setItems(new String[]{"同机对弈", "局域网联机"}, (d, which) -> {
                    if (which == 0) {
                        startBanqi("local");
                    } else {
                        Intent i = new Intent(this, LanLobbyActivity.class);
                        i.putExtra(LanLobbyActivity.EXTRA_GAME, "banqi");
                        startActivity(i);
                    }
                })
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void showBanqiRules() {
        UiTheme t = UiTheme.current(this);
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "暗棋 · 玩法说明")
                .setMessage("4 × 8 棋盘，32 枚棋子全部背朝上，先手先翻。\n"
                        + "每回合二选一：翻任意一枚暗子，或走一格己方明子（可吃子）。\n"
                        + "首翻定色：先手翻出的颜色归先手，另一方执另一色。\n"
                        + "军衔：將 > 士 > 象 > 車 > 馬 > 炮 > 卒，同级可互吃；\n"
                        + "卒可吃將，將不能吃卒。\n"
                        + "炮走一格空格，吃子必须隔恰好一枚炮架跳吃。\n\n"
                        + "无合法步判负；连续 30 步无吃子无翻子判和；\n"
                        + "同一局面出现三次判和。联机时暗子身份只由房主裁决。")
                .setPositiveButton("知道了", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    private void showNewGameDialog() {
        UiTheme t = UiTheme.current(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_newgame, null);
        SeekBar sbLevel = view.findViewById(R.id.sbLevel);
        SeekBar sbHandi = view.findViewById(R.id.sbHandicap);
        TextView tvLevel = view.findViewById(R.id.tvLevel);
        TextView tvHandi = view.findViewById(R.id.tvHandicap);
        RadioButton rbBlack = view.findViewById(R.id.rbBlack);
        RadioButton rbWhite = view.findViewById(R.id.rbWhite);
        RadioButton rbPachi = view.findViewById(R.id.rbPachi);
        RadioButton rbGnu = view.findViewById(R.id.rbGnu);
        RadioButton rbKatago = view.findViewById(R.id.rbKatago);

        // ---- 主题化: 标题/印记/发丝线/标签/单选圆点/滑杆/主按钮 ----
        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);
        float d = getResources().getDisplayMetrics().density;
        TextView tvTitle = view.findViewById(R.id.tvDlgTitle);
        tvTitle.setTextColor(t.titleColor);
        tvTitle.setTypeface(serif);
        tvTitle.setLetterSpacing(0.08f);
        android.graphics.drawable.GradientDrawable mk = new android.graphics.drawable.GradientDrawable();
        mk.setColor(t.accent);
        if (t.id == UiTheme.HARA) mk.setCornerRadius(2 * d);
        else mk.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        view.findViewById(R.id.tvDlgMark).setBackground(mk);
        view.findViewById(R.id.divDlg).setBackgroundColor(t.dlgLine);
        int[] labels = {R.id.tvLbEngine, R.id.tvLbColor, R.id.tvLbLevel, R.id.tvLbHandi, R.id.tvLbSound};
        for (int id : labels) {
            TextView lb = view.findViewById(id);
            lb.setTextColor(t.subColor);
            lb.setLetterSpacing(0.1f);
        }
        for (RadioButton rb : new RadioButton[]{rbBlack, rbWhite, rbPachi, rbGnu, rbKatago}) {
            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_checked}, t.radioDrawable(true));
            sld.addState(new int[]{-android.R.attr.state_checked}, t.radioDrawable(false));
            rb.setButtonDrawable(sld);
            rb.setTextColor(t.titleColor);
        }
        for (SeekBar sb : new SeekBar[]{sbLevel, sbHandi}) {
            sb.setProgressDrawable(t.seekTrack());
            sb.setThumb(t.seekThumb());
        }
        tvLevel.setTextColor(t.titleColor);
        tvLevel.setTypeface(serif);
        tvHandi.setTextColor(t.titleColor);
        tvHandi.setTypeface(serif);
        android.widget.Button btnStart = view.findViewById(R.id.btnStart);
        styleDialogPrimaryButton(t, btnStart, d);

        sbLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean f) { tvLevel.setText((p + 1) + " 级"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        sbHandi.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                tvHandi.setText(p == 0 ? "不让子" : "让 " + p + " 子");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        boolean soundOn = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("go_sound", true);
        TextView tvSound = view.findViewById(R.id.tvSound);
        tvSound.setText(soundOn ? "开" : "关");
        tvSound.setTextColor(soundOn ? t.accent : t.subColor);
        tvSound.setTypeface(serif);
        tvSound.setOnClickListener(v -> {
            boolean next = !getSharedPreferences("ui", MODE_PRIVATE).getBoolean("go_sound", true);
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("go_sound", next).apply();
            tvSound.setText(next ? "开" : "关");
            tvSound.setTextColor(next ? t.accent : t.subColor);
        });

        AlertDialog dlg = new AlertDialog.Builder(this).setView(view).create();
        btnStart.setOnClickListener(v -> {
            dlg.dismiss();
            Intent i = new Intent(this, GameActivity.class);
            i.putExtra(GameActivity.EXTRA_MODE, "gnu");
            i.putExtra(GameActivity.EXTRA_ENGINE, rbKatago.isChecked() ? GtpEngine.ENGINE_KATAGO : rbPachi.isChecked() ? GtpEngine.ENGINE_PACHI : GtpEngine.ENGINE_GNU);
            i.putExtra(GameActivity.EXTRA_COLOR, rbBlack.isChecked() ? GoGame.BLACK : GoGame.WHITE);
            i.putExtra(GameActivity.EXTRA_LEVEL, sbLevel.getProgress() + 1);
            i.putExtra(GameActivity.EXTRA_HANDICAP, sbHandi.getProgress());
            startActivity(i);
        });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(t.dialogBackground());
    }

    /** 弹窗主按钮: 墨白=墨色实底, 粉彩=绿色 pill(弹窗里没有网格容器可依托) */
    private void styleDialogPrimaryButton(UiTheme t, android.widget.Button b, float d) {
        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);
        if (t.hairlineList) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.primaryBg);
            g.setCornerRadius(2 * d);
            b.setBackground(g);
            b.setTextColor(t.primaryText);
        } else {
            b.setBackground(t.buttonDrawable(1));
            b.setTextColor(t.buttonTextColor(1));
        }
        b.setTypeface(serif);
        b.setLetterSpacing(0.2f);
    }

    private void showXiangqiNewGameDialog() {
        UiTheme t = UiTheme.current(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_xiangqi, null);
        SeekBar sbLevel = view.findViewById(R.id.sbLevel);
        TextView tvLevel = view.findViewById(R.id.tvLevel);
        TextView tvLbLevel = view.findViewById(R.id.tvLbLevel);
        TextView tvLbHints = view.findViewById(R.id.tvLbHints);
        TextView tvHints = view.findViewById(R.id.tvHints);
        RadioButton rbRed = view.findViewById(R.id.rbRed);
        RadioButton rbBlack = view.findViewById(R.id.rbBlack);
        RadioButton rbFairy = view.findViewById(R.id.rbFairy);
        RadioButton rbPikafish = view.findViewById(R.id.rbPikafish);

        android.graphics.Typeface serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);
        float d = getResources().getDisplayMetrics().density;
        TextView tvTitle = view.findViewById(R.id.tvDlgTitle);
        tvTitle.setTextColor(t.titleColor);
        tvTitle.setTypeface(serif);
        tvTitle.setLetterSpacing(0.08f);
        GradientDrawable mk = new GradientDrawable();
        mk.setColor(t.accent);
        if (t.hairlineList) mk.setCornerRadius(2 * d);
        else mk.setShape(GradientDrawable.OVAL);
        view.findViewById(R.id.tvDlgMark).setBackground(mk);
        view.findViewById(R.id.divDlg).setBackgroundColor(t.dlgLine);
        int[] labels = {R.id.tvLbEngine, R.id.tvLbColor, R.id.tvLbLevel, R.id.tvLbHints};
        for (int id : labels) {
            TextView lb = view.findViewById(id);
            lb.setTextColor(t.subColor);
            lb.setLetterSpacing(0.1f);
        }
        for (RadioButton rb : new RadioButton[]{rbFairy, rbPikafish, rbRed, rbBlack}) {
            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_checked}, t.radioDrawable(true));
            sld.addState(new int[]{-android.R.attr.state_checked}, t.radioDrawable(false));
            rb.setButtonDrawable(sld);
            rb.setTextColor(t.titleColor);
        }
        sbLevel.setProgressDrawable(t.seekTrack());
        sbLevel.setThumb(t.seekThumb());
        tvLevel.setTextColor(t.titleColor);
        tvLevel.setTypeface(serif);
        android.widget.Button btnStart = view.findViewById(R.id.btnStart);
        styleDialogPrimaryButton(t, btnStart, d);

        boolean hintsOn = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("xq_hints", true);
        tvHints.setText(hintsOn ? "开" : "关");
        tvHints.setTextColor(hintsOn ? t.accent : t.subColor);
        tvHints.setTypeface(serif);
        tvHints.setOnClickListener(v -> {
            boolean next = !getSharedPreferences("ui", MODE_PRIVATE).getBoolean("xq_hints", true);
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("xq_hints", next).apply();
            tvHints.setText(next ? "开" : "关");
            tvHints.setTextColor(next ? t.accent : t.subColor);
        });

        sbLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                if (rbFairy.isChecked()) tvLevel.setText(XiangqiEngine.eloForLevel(p + 1) + " Elo");
                else tvLevel.setText((p + 1) + " 级");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        rbFairy.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) return;
            tvLbLevel.setText("棋力等级 (1-10)");
            tvLevel.setText(XiangqiEngine.eloForLevel(sbLevel.getProgress() + 1) + " Elo");
        });
        rbPikafish.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) return;
            tvLbLevel.setText("引擎等级 (1-10)");
            tvLevel.setText((sbLevel.getProgress() + 1) + " 级");
        });

        AlertDialog dlg = new AlertDialog.Builder(this).setView(view).create();
        btnStart.setOnClickListener(v -> {
            dlg.dismiss();
            Intent i = new Intent(this, XiangqiActivity.class);
            i.putExtra(XiangqiActivity.EXTRA_MODE, "ai");
            i.putExtra(XiangqiActivity.EXTRA_ENGINE, rbPikafish.isChecked()
                    ? XiangqiEngine.ENGINE_PIKAFISH : XiangqiEngine.ENGINE_FAIRY);
            i.putExtra(XiangqiActivity.EXTRA_COLOR, rbRed.isChecked() ? XiangqiGame.RED : XiangqiGame.BLACK);
            i.putExtra(XiangqiActivity.EXTRA_LEVEL, sbLevel.getProgress() + 1);
            startActivity(i);
        });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(t.dialogBackground());
    }

    private void showSgfList() {
        try {
            String[] files = getAssets().list("sgf");
            List<String> names = new ArrayList<>();
            if (files != null) {
                for (String f : files) if (f.toLowerCase().endsWith(".sgf")) names.add(f);
            }
            Collections.sort(names);
            List<String> labels = new ArrayList<>();
            for (String f : names) labels.add("内置 · " + f.substring(0, f.length() - 4));
            labels.add("从文件导入 SGF…");
            String[] arr = labels.toArray(new String[0]);
            String report = names.isEmpty() ? "没有内置棋谱" : "选择棋谱";
            UiTheme t = UiTheme.current(this);
            AlertDialog dlg = UiTheme.themedBuilder(this, t, report)
                    .setItems(arr, (d, which) -> {
                        if (which == names.size()) importSgf();
                        else openSgf("sgf/" + names.get(which));
                    })
                    .create();
            dlg.show();
            UiTheme.styleDialog(dlg, t, false);
        } catch (Exception e) {
            Toast.makeText(this, "棋谱列表失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 系统文件选择器导入 SGF(任意编码/主变), 复制到缓存后打开打谱页 */
    private void importSgf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN_SGF);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 象棋棋谱: PGN(ICCS 坐标着法) 或 FEN 局面 */
    private void importXiangqiRecord() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN_RECORD);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_OPEN_SGF) handleSgfImport(data.getData());
        else if (requestCode == REQ_OPEN_RECORD) handleRecordImport(data.getData());
    }

    private void handleSgfImport(Uri uri) {
        try {
            String name = displayName(uri);
            if (name == null) name = "pattern";
            if (!name.toLowerCase().endsWith(".sgf")) name += ".sgf";
            File out = copyUri(uri, name);
            Sgf.Game check = Sgf.load(out);
            if (check.nodes.isEmpty()) {
                Toast.makeText(this, "棋谱里没有可读的着法", Toast.LENGTH_LONG).show();
                return;
            }
            Intent i = new Intent(this, GameActivity.class);
            i.putExtra(GameActivity.EXTRA_MODE, "review");
            i.putExtra(GameActivity.EXTRA_SGF, out.getAbsolutePath());
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.e("Sgf", "import failed", e);
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleRecordImport(Uri uri) {
        try {
            String name = displayName(uri);
            File out = copyUri(uri, name == null ? "record.pgn" : name);
            XiangqiPgn.Record rec = XiangqiPgn.load(out);
            XiangqiGame check = new XiangqiGame();
            int ok = XiangqiPgn.applyAll(rec, check);
            if (ok == 0 && rec.baseFen == null) {
                Toast.makeText(this, "没有可读的着法或 FEN 局面", Toast.LENGTH_LONG).show();
                return;
            }
            if (!rec.warning.isEmpty())
                Toast.makeText(this, rec.warning, Toast.LENGTH_LONG).show();
            Intent i = new Intent(this, XiangqiActivity.class);
            i.putExtra(XiangqiActivity.EXTRA_MODE, "review");
            i.putExtra(XiangqiActivity.EXTRA_RECORD, out.getAbsolutePath());
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.e("XiangqiPgn", "import failed", e);
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private File copyUri(Uri uri, String name) throws Exception {
        File out = new File(getCacheDir(), "import_" + System.currentTimeMillis() + "_"
                + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new Exception("无法读取文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        return name;
    }

    /** assets 里的棋谱先释放到缓存目录再打开 */
    private void openSgf(String assetPath) {
        try {
            File out = new File(getCacheDir(), new File(assetPath).getName());
            try (InputStream in = getAssets().open(assetPath);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            Intent i = new Intent(this, GameActivity.class);
            i.putExtra(GameActivity.EXTRA_MODE, "review");
            i.putExtra(GameActivity.EXTRA_SGF, out.getAbsolutePath());
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "棋谱打开失败", Toast.LENGTH_SHORT).show();
        }
    }
}
