package com.sijia.go.banqi;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.sijia.go.R;
import com.sijia.go.UiTheme;

import java.util.Random;

/**
 * 暗棋单机页：人机对弈 / 本地双人。
 * 横屏布局，常驻只保留轮次与红/黑/暗计数，悔棋、认输、难度、重开、返回主页收进右侧浮层。
 * 系统返回键、长按棋盘、右下 ⋯ 三处都能呼出浮层。
 */
public class BanqiActivity extends ComponentActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_LEVEL = "level";

    private static final String PREF_LEVEL = "banqi_level";
    /** 人机模式玩家固定座位 1，AI 座位 2。 */
    private static final int PLAYER_SEAT = BanqiGame.SEAT_FIRST;
    private static final int AI_SEAT = BanqiGame.SEAT_SECOND;

    private BanqiGame game = new BanqiGame();
    private BanqiBoardView boardView;
    private TextView tvTurn, tvCount, tvLevelValue, tvPanelTitle, btnPanelClose, btnMore;
    private TextView rowUndo, rowResign, rowRestart, rowHome;
    private LinearLayout panel, panelHead, rowLevel;
    private View scrim, divHead, divUndo, divResign, divLevel, divRestart, divHome;

    private UiTheme theme;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    /** 落子音效，音频资源与播放入口沿用象棋（R.raw.xq_move）。 */
    private SoundPool soundPool;
    private int sndMove;

    private String mode = "ai";
    private int level = 5;
    private int selX = -1;
    private int selY = -1;
    private boolean thinking;
    private boolean panelOpen;
    private boolean exitDialogShowing;
    private boolean resultShown;

    private final Runnable aiRunnable = new Runnable() {
        @Override
        public void run() {
            thinking = false;
            if (isFinishing() || isDestroyed()) return;
            if (!game.isOver() && "ai".equals(mode) && game.turnSeat() == AI_SEAT) {
                BanqiGame.Move m = BanqiAI.choose(game, level, random);
                if (m != null) {
                    game.play(m);
                    playMoveSound();
                }
            }
            clearSelection();
            refresh();
            if (game.isOver()) showGameOver();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_banqi);
        initSounds();

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null || mode.isEmpty()) mode = "ai";
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        int extraLevel = getIntent().getIntExtra(EXTRA_LEVEL, 5);
        // 主菜单固定传 5；有保存过的难度时优先沿用，保证「记住难度」生效。
        level = extraLevel == 5 ? sp.getInt(PREF_LEVEL, 5) : extraLevel;
        level = Math.max(1, Math.min(10, level));

        boardView = findViewById(R.id.boardView);
        tvTurn = findViewById(R.id.tvTurn);
        tvCount = findViewById(R.id.tvCount);
        tvLevelValue = findViewById(R.id.tvLevelValue);
        tvPanelTitle = findViewById(R.id.tvPanelTitle);
        btnPanelClose = findViewById(R.id.btnPanelClose);
        btnMore = findViewById(R.id.btnMore);
        rowUndo = findViewById(R.id.rowUndo);
        rowResign = findViewById(R.id.rowResign);
        rowRestart = findViewById(R.id.rowRestart);
        rowHome = findViewById(R.id.rowHome);
        panel = findViewById(R.id.panel);
        panelHead = findViewById(R.id.panelHead);
        rowLevel = findViewById(R.id.rowLevel);
        scrim = findViewById(R.id.scrim);
        divHead = findViewById(R.id.divHead);
        divUndo = findViewById(R.id.divUndo);
        divResign = findViewById(R.id.divResign);
        divLevel = findViewById(R.id.divLevel);
        divRestart = findViewById(R.id.divRestart);
        divHome = findViewById(R.id.divHome);

        game = new BanqiGame(System.nanoTime());
        boardView.setGame(game);
        boardView.setOnCellTapListener(new BanqiBoardView.OnCellTapListener() {
            @Override
            public void onCellTap(int x, int y) {
                onTap(x, y);
            }

            @Override
            public void onBoardLongPress() {
                openPanel();
            }
        });

        btnMore.setOnClickListener(v -> openPanel());
        btnPanelClose.setOnClickListener(v -> closePanel());
        scrim.setOnClickListener(v -> closePanel());
        rowUndo.setOnClickListener(v -> {
            closePanel();
            onUndo();
        });
        rowResign.setOnClickListener(v -> {
            closePanel();
            confirmResign();
        });
        rowLevel.setOnClickListener(v -> cycleLevel());
        rowRestart.setOnClickListener(v -> {
            closePanel();
            restart();
        });
        rowHome.setOnClickListener(v -> {
            closePanel();
            confirmHome();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (panelOpen) closePanel();
                else openPanel();
            }
        });

        applyTheme(UiTheme.current(this));
        refresh();
        if ("ai".equals(mode) && game.turnSeat() == AI_SEAT) scheduleAi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 音效（与象棋同一份资源、同一套 SoundPool 播放入口）
    // ------------------------------------------------------------------

    private void initSounds() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sndMove = soundPool.load(this, R.raw.xq_move, 1);
    }

    /** 翻子、走子、吃子、AI 落子统一走这里。 */
    private void playMoveSound() {
        if (soundPool != null) soundPool.play(sndMove, 0.75f, 0.75f, 1, 0, 1f);
    }

    // ------------------------------------------------------------------
    // 落子交互
    // ------------------------------------------------------------------

    private boolean canTap() {
        if (game.isOver() || thinking) return false;
        return !"ai".equals(mode) || game.turnSeat() == PLAYER_SEAT;
    }

    private void onTap(int x, int y) {
        if (!canTap()) return;
        if (game.isHiddenAt(x, y)) {
            clearSelection();
            if (game.flip(x, y)) afterAction();
            return;
        }
        if (!game.hasPieceAt(x, y)) {
            if (selX >= 0) {
                if (tryPlayTo(x, y)) return;
                toast("这步不合规则");
            }
            clearSelection();
            return;
        }
        int p = game.pieceCodeAt(x, y);
        if (BanqiGame.colorOf(p) == game.turnColor()) {
            select(x, y);
            return;
        }
        if (selX >= 0) {
            if (tryPlayTo(x, y)) return;
            toast("不能吃这枚子");
            clearSelection();
            return;
        }
        toast("请先选中己方明子");
    }

    private void select(int x, int y) {
        selX = x;
        selY = y;
        boardView.setSelected(x, y);
        boardView.setLegalTargets(game.legalMovesFrom(x, y));
    }

    private void clearSelection() {
        selX = -1;
        selY = -1;
        if (boardView != null) boardView.clearSelection();
    }

    private boolean tryPlayTo(int tx, int ty) {
        if (selX < 0 || selY < 0) return false;
        boolean legal = false;
        for (BanqiGame.Move m : game.legalMovesFrom(selX, selY)) {
            if (m.tx == tx && m.ty == ty) {
                legal = true;
                break;
            }
        }
        if (!legal) return false;
        if (!game.play(selX, selY, tx, ty)) return false;
        clearSelection();
        afterAction();
        return true;
    }

    private void afterAction() {
        playMoveSound();
        refresh();
        if (game.isOver()) {
            showGameOver();
            return;
        }
        if ("ai".equals(mode) && game.turnSeat() == AI_SEAT) scheduleAi();
    }

    private void scheduleAi() {
        if (thinking || game.isOver()) return;
        thinking = true;
        boardView.setInteractive(false);
        ui.postDelayed(aiRunnable, 450L);
    }

    // ------------------------------------------------------------------
    // 面板操作
    // ------------------------------------------------------------------

    private void onUndo() {
        if (thinking) return;
        int n = 0;
        if ("ai".equals(mode)) {
            if (game.plies() > 0) {
                game.undo();
                n++;
            }
            if (game.plies() > 0 && game.turnSeat() != PLAYER_SEAT) {
                game.undo();
                n++;
            }
        } else if (game.undo() != null) {
            n = 1;
        }
        if (n == 0) {
            toast("还没有落子可悔");
            return;
        }
        resultShown = false;
        clearSelection();
        refresh();
    }

    private void confirmResign() {
        if (game.isOver() || thinking) return;
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        final int seat = "ai".equals(mode) ? PLAYER_SEAT : game.turnSeat();
        String who = game.colorOfSeat(seat) == BanqiGame.RED ? "红方"
                : game.colorOfSeat(seat) == BanqiGame.BLACK ? "黑方" : "当前一方";
        AlertDialog dlg = UiTheme.themedBuilder(this, t, "认 输")
                .setMessage("确认由" + who + "认输?")
                .setPositiveButton("认输", (d, w) -> {
                    game.resign(seat);
                    clearSelection();
                    refresh();
                    showGameOver();
                })
                .setNegativeButton("继续", null)
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, true);
    }

    private void restart() {
        ui.removeCallbacks(aiRunnable);
        game.reset(System.nanoTime());
        thinking = false;
        resultShown = false;
        clearSelection();
        refresh();
        if ("ai".equals(mode) && game.turnSeat() == AI_SEAT) scheduleAi();
    }

    private void cycleLevel() {
        level = level >= 10 ? 1 : level + 1;
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt(PREF_LEVEL, level).apply();
        tvLevelValue.setText(level + " 段");
    }

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        tvLevelValue.setText(level + " 段");
        scrim.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        scrim.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
    }

    private void showGameOver() {
        if (resultShown || isFinishing()) return;
        resultShown = true;
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        String title = game.result() == BanqiGame.RESULT_DRAW ? "和 棋"
                : (game.result() == BanqiGame.RED ? "红方胜" : "黑方胜");
        AlertDialog dlg = UiTheme.themedBuilder(this, t, title)
                .setMessage(game.endReason())
                .setPositiveButton("再来一局", (d, w) -> restart())
                .setNegativeButton("返回主页", (d, w) -> finish())
                .create();
        dlg.show();
        UiTheme.styleDialog(dlg, t, false);
    }

    /** 返回主页确认：自定义圆角卡片，取消 / 结束两个按钮。 */
    private void confirmHome() {
        if (exitDialogShowing || isFinishing()) return;
        UiTheme t = theme != null ? theme : UiTheme.current(this);
        float d = getResources().getDisplayMetrics().density;
        View v = getLayoutInflater().inflate(R.layout.dialog_banqi, null);
        TextView title = v.findViewById(R.id.tvDlgTitle);
        TextView msg = v.findViewById(R.id.tvDlgMessage);
        View mark = v.findViewById(R.id.tvDlgMark);
        View div = v.findViewById(R.id.divDlg);
        Button cancel = v.findViewById(R.id.btnCancel);
        Button end = v.findViewById(R.id.btnEnd);

        title.setTextColor(t.titleColor);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        GradientDrawable mk = new GradientDrawable();
        mk.setColor(t.accent);
        if (t.hairlineList) mk.setCornerRadius(2 * d);
        else mk.setShape(GradientDrawable.OVAL);
        mark.setBackground(mk);
        div.setBackgroundColor(t.dlgLine);
        msg.setTextColor(t.subColor);
        msg.setText("结束本局并返回主页?");
        styleDialogButton(cancel, t, 0);
        styleDialogButton(end, t, 2);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        cancel.setOnClickListener(x -> dlg.dismiss());
        end.setOnClickListener(x -> {
            dlg.dismiss();
            finish();
        });
        dlg.setOnDismissListener(x -> exitDialogShowing = false);
        exitDialogShowing = true;
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(t.dialogBackground());
    }

    private void styleDialogButton(Button b, UiTheme t, int kind) {
        float d = getResources().getDisplayMetrics().density;
        b.setBackground(t.buttonDrawable(kind));
        b.setTextColor(t.buttonTextColor(kind));
        b.setTypeface(Typeface.create(t.hairlineList ? "serif" : "sans-serif", Typeface.NORMAL));
        b.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void refresh() {
        boardView.setGame(game);
        boardView.setInteractive(canTap());
        if (selX >= 0 && selY >= 0 && game.isFaceUp(selX, selY)) {
            boardView.setSelected(selX, selY);
            boardView.setLegalTargets(game.legalMovesFrom(selX, selY));
        } else {
            clearSelection();
        }
        refreshHud();
    }

    private void refreshHud() {
        int color = game.turnColor();
        String label;
        if (color == BanqiGame.RED) {
            label = "红方行棋";
        } else if (color == BanqiGame.BLACK) {
            label = "黑方行棋";
        } else {
            label = game.turnSeat() == BanqiGame.SEAT_FIRST ? "先手行棋" : "后手行棋";
        }
        SpannableString s = new SpannableString("● " + label);
        s.setSpan(new ForegroundColorSpan(sideColor(color)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(theme != null ? theme.titleColor : Color.BLACK),
                2, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTurn.setText(s);

        int red = 0;
        int black = 0;
        int hidden = 0;
        for (int y = 0; y < BanqiGame.ROWS; y++) {
            for (int x = 0; x < BanqiGame.COLS; x++) {
                int p = game.pieceCodeAt(x, y);
                if (p == BanqiGame.EMPTY) continue;
                if (!game.isFaceUp(x, y)) {
                    hidden++;
                } else if (BanqiGame.colorOf(p) == BanqiGame.RED) {
                    red++;
                } else {
                    black++;
                }
            }
        }
        tvCount.setText("红 " + red + " · 黑 " + black + " · 暗 " + hidden);
    }

    private int sideColor(int color) {
        boolean hara = theme != null && theme.hairlineList;
        if (color == BanqiGame.RED) return hara ? 0xFFA93226 : 0xFFC0553F;
        if (color == BanqiGame.BLACK) return hara ? 0xFF1A1A1A : 0xFF3E3A36;
        return theme != null ? theme.subColor : Color.GRAY;
    }

    // ------------------------------------------------------------------
    // 主题
    // ------------------------------------------------------------------

    private void applyTheme(UiTheme t) {
        theme = t;
        boolean hara = t.hairlineList;
        float d = getResources().getDisplayMetrics().density;

        getWindow().setStatusBarColor(t.windowBg);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        decor.setBackgroundColor(t.windowBg);
        findViewById(R.id.root).setBackgroundColor(t.windowBg);

        tvTurn.setTextColor(t.titleColor);
        tvTurn.setTypeface(Typeface.create("serif", hara ? Typeface.NORMAL : Typeface.BOLD));
        tvCount.setTextColor(t.subColor);
        btnMore.setTextColor(Color.argb(150, Color.red(t.titleColor),
                Color.green(t.titleColor), Color.blue(t.titleColor)));

        int redPiece = 0xFFA93226;
        int blackPiece = 0xFF1A1A1A;
        boardView.setThemeColors(t.boardBg, t.boardLine, redPiece, blackPiece, t.accent, t.marker);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(t.windowBg);
        panelBg.setCornerRadius((hara ? 6 : 20) * d);
        panel.setBackground(panelBg);
        int pad = hara ? 0 : (int) (10 * d);
        panel.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams hp = (LinearLayout.LayoutParams) panelHead.getLayoutParams();
        hp.height = (int) ((hara ? 52 : 42) * d);
        panelHead.setLayoutParams(hp);
        int hpad = (int) ((hara ? 20 : 8) * d);
        panelHead.setPadding(hpad, 0, hpad, 0);

        tvPanelTitle.setTextColor(t.subColor);
        btnPanelClose.setTextColor(t.subColor);

        styleDivider(divHead, hara, t);
        styleDivider(divUndo, hara, t);
        styleDivider(divResign, hara, t);
        styleDivider(divLevel, hara, t);
        styleDivider(divRestart, hara, t);
        styleDivider(divHome, hara, t);

        styleRow(rowUndo, false, t);
        styleRow(rowResign, true, t);
        styleRow(rowRestart, false, t);
        styleRow(rowHome, true, t);
        styleLevelRow(t);
        tvLevelValue.setText(level + " 段");
    }

    private void styleDivider(View v, boolean hara, UiTheme t) {
        v.setVisibility(hara ? View.VISIBLE : View.GONE);
        v.setBackgroundColor(t.dividerColor);
    }

    private void styleRow(TextView row, boolean danger, UiTheme t) {
        boolean hara = t.hairlineList;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        lp.height = (int) ((hara ? 54 : 48) * d);
        if (hara) {
            lp.setMargins(0, 0, 0, 0);
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setPadding((int) (20 * d), 0, (int) (20 * d), 0);
        } else {
            int m = (int) (3.5f * d);
            lp.setMargins(0, m, 0, m);
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.btnBg);
            g.setCornerRadius(14 * d);
            row.setBackground(g);
            row.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        }
        row.setLayoutParams(lp);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTextSize(hara ? 15 : 14);
        row.setTypeface(Typeface.create(hara ? "serif" : "sans-serif", Typeface.NORMAL));
        row.setLetterSpacing(hara ? 0.14f : 0.1f);
        row.setTextColor(danger ? t.dangerText : t.titleColor);
    }

    private void styleLevelRow(UiTheme t) {
        boolean hara = t.hairlineList;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rowLevel.getLayoutParams();
        lp.height = (int) ((hara ? 54 : 48) * d);
        if (hara) {
            lp.setMargins(0, 0, 0, 0);
            rowLevel.setBackgroundColor(Color.TRANSPARENT);
            rowLevel.setPadding((int) (20 * d), 0, (int) (20 * d), 0);
        } else {
            int m = (int) (3.5f * d);
            lp.setMargins(0, m, 0, m);
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.btnBg);
            g.setCornerRadius(14 * d);
            rowLevel.setBackground(g);
            rowLevel.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        }
        rowLevel.setLayoutParams(lp);

        TextView label = findViewById(R.id.tvLevelLabel);
        label.setTextSize(hara ? 15 : 14);
        label.setTypeface(Typeface.create(hara ? "serif" : "sans-serif", Typeface.NORMAL));
        label.setLetterSpacing(hara ? 0.14f : 0.1f);
        label.setTextColor(t.titleColor);

        tvLevelValue.setTextColor(hara ? t.footColor : t.subColor);
        tvLevelValue.setTextSize(10.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
