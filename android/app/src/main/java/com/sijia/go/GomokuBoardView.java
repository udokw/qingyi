package com.sijia.go;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 五子棋棋盘 View：复用 19 路围棋棋盘的尺寸、外描边、木色与双主题配色。
 * 额外绘制获胜五连线与 AI 提示点。
 */
public class GomokuBoardView extends View {
    public interface OnBoardTapListener { void onBoardTap(int x, int y); }

    private static final int MAX_N = 25;
    private int N = GomokuGame.SIZE;
    private int[][] board = new int[MAX_N][MAX_N];
    private int lastX = -1, lastY = -1;
    /** 获胜五连首尾 {x1,y1,x2,y2} */
    private int[] winLine;
    private int hintX = -1, hintY = -1;
    private OnBoardTapListener listener;
    private boolean inputEnabled = true;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cell;
    private float margin;

    private int boardBgColor = Color.parseColor("#E3B771");
    private int markerColor = Color.parseColor("#3D7AFD");

    public GomokuBoardView(Context ctx) { super(ctx); init(); }
    public GomokuBoardView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        linePaint.setColor(Color.rgb(60, 40, 20));
        linePaint.setStrokeWidth(2f);
    }

    /** 主题配色：棋盘底色/线色/最后一手与提示标记色 */
    public void setThemeColors(int bg, int line, int marker) {
        boardBgColor = bg;
        markerColor = marker;
        linePaint.setColor(line);
        invalidate();
    }

    public void setOnBoardTapListener(OnBoardTapListener l) { listener = l; }
    public void setInputEnabled(boolean e) { inputEnabled = e; }

    public void setBoard(int[][] b, int lx, int ly) {
        board = b;
        lastX = lx;
        lastY = ly;
        invalidate();
    }

    public void setBoardSize(int n) {
        N = Math.max(2, Math.min(MAX_N, n));
        lastX = lastY = -1;
        winLine = null;
        hintX = hintY = -1;
        invalidate();
    }

    /** 直接绑定棋局：棋子 + 最后一手 + 获胜五连。 */
    public void setGame(GomokuGame g) {
        if (g == null) return;
        setBoardSize(g.size());
        board = g.board();
        lastX = g.lastX();
        lastY = g.lastY();
        winLine = g.winLine();
        invalidate();
    }

    public void setWinLine(int[] line) { winLine = line; invalidate(); }

    public void setHint(int hx, int hy) {
        hintX = hx;
        hintY = hy;
        invalidate();
    }

    public void clearHint() { hintX = hintY = -1; invalidate(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int s = (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED)
                ? w : Math.min(w, MeasureSpec.getSize(hSpec));
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        // 与围棋盘完全一致: 最外线贴边, 只留最外侧棋子半径 + 2px 抗锯齿余量
        margin = (0.47f * w / (N - 1) + 2f) / (1f + 0.94f / (N - 1));
        cell = (w - 2 * margin) / (N - 1);

        cv.drawColor(boardBgColor);

        // 外描边: 复刻 iOS(1pt 线, 内缩 0.5pt, 70% 不透明)
        float sw = dp(1f);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(sw);
        framePaint.setColor(linePaint.getColor());
        framePaint.setAlpha(179);
        float fi = sw / 2f + 1f;
        cv.drawRect(fi, fi, w - fi, getHeight() - fi, framePaint);

        // 网格
        for (int i = 0; i < N; i++) {
            float p = margin + i * cell;
            cv.drawLine(margin, p, w - margin, p, linePaint);
            cv.drawLine(p, margin, p, w - margin, linePaint);
        }
        linePaint.setStrokeWidth(4f);
        cv.drawLine(margin, margin, w - margin, margin, linePaint);
        cv.drawLine(margin, w - margin, w - margin, w - margin, linePaint);
        cv.drawLine(margin, margin, margin, w - margin, linePaint);
        cv.drawLine(w - margin, margin, w - margin, w - margin, linePaint);
        linePaint.setStrokeWidth(2f);

        // 星位（19 路同围棋）
        int[] stars = N == 19 ? new int[]{3, 9, 15}
                : N == 13 ? new int[]{3, 6, 9}
                : N == 9 ? new int[]{3, 5} : new int[0];
        for (int sx : stars) for (int sy : stars) {
            cv.drawCircle(px(sx), py(sy), cell * 0.09f, linePaint);
        }

        // 棋子
        float r = cell * 0.47f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int v = board[y][x];
                if (v == GomokuGame.EMPTY) continue;
                drawStone(cv, px(x), py(y), r, v == GomokuGame.BLACK);
            }
        }

        // 获胜五连高亮
        if (winLine != null && winLine.length >= 4) {
            markPaint.setShader(null);
            markPaint.setStyle(Paint.Style.STROKE);
            markPaint.setStrokeWidth(Math.max(3f, cell * 0.10f));
            markPaint.setStrokeCap(Paint.Cap.ROUND);
            markPaint.setColor(markerColor);
            markPaint.setAlpha(220);
            cv.drawLine(px(winLine[0]), py(winLine[1]),
                    px(winLine[2]), py(winLine[3]), markPaint);
            markPaint.setAlpha(255);
            markPaint.setStrokeCap(Paint.Cap.BUTT);
        }

        // AI 提示点：外圈 + 中心点
        if (hintX >= 0 && hintY >= 0 && hintX < N && hintY < N
                && board[hintY][hintX] == GomokuGame.EMPTY) {
            markPaint.setShader(null);
            markPaint.setStyle(Paint.Style.STROKE);
            markPaint.setStrokeWidth(Math.max(2f, cell * 0.06f));
            markPaint.setColor(markerColor);
            cv.drawCircle(px(hintX), py(hintY), r * 0.72f, markPaint);
            markPaint.setStyle(Paint.Style.FILL);
            cv.drawCircle(px(hintX), py(hintY), r * 0.16f, markPaint);
            markPaint.setStyle(Paint.Style.FILL);
        }

        // 最后落子标记
        if (lastX >= 0 && lastY >= 0 && lastX < N && lastY < N
                && board[lastY][lastX] != GomokuGame.EMPTY) {
            stonePaint.setShader(null);
            stonePaint.setStyle(Paint.Style.STROKE);
            stonePaint.setStrokeWidth(3f);
            stonePaint.setColor(board[lastY][lastX] == GomokuGame.BLACK
                    ? Color.WHITE : markerColor);
            cv.drawCircle(px(lastX), py(lastY), r * 0.5f, stonePaint);
            stonePaint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawStone(Canvas cv, float cx, float cy, float r, boolean black) {
        if (black) {
            stonePaint.setShader(new RadialGradient(cx - r / 3, cy - r / 3, r,
                    new int[]{Color.rgb(90, 90, 90), Color.rgb(20, 20, 20)},
                    null, Shader.TileMode.CLAMP));
        } else {
            stonePaint.setShader(new RadialGradient(cx - r / 3, cy - r / 3, r,
                    new int[]{Color.WHITE, Color.rgb(200, 200, 200)},
                    null, Shader.TileMode.CLAMP));
        }
        stonePaint.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, r, stonePaint);
        stonePaint.setShader(null);
    }

    private float px(int x) { return margin + x * cell; }
    private float py(int y) { return margin + y * cell; }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!inputEnabled || listener == null) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int x = Math.round((e.getX() - margin) / cell);
            int y = Math.round((e.getY() - margin) / cell);
            if (x >= 0 && x < N && y >= 0 && y < N) listener.onBoardTap(x, y);
        }
        return true;
    }
}
