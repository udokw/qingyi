package com.sijia.go;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 围棋棋盘 View: 绘制棋盘/棋子/星位/最后落子标记/形势点, 触摸落子.
 */
public class GoBoardView extends View {
    public interface OnBoardTapListener { void onBoardTap(int x, int y); }

    private static final int MAX_N = 25;
    private int N = GoGame.SIZE;
    private int[][] board = new int[MAX_N][MAX_N];
    private int lastX = -1, lastY = -1;
    private int[][] territory;           // 形势判断归属(0空 1黑 2白), null 不显示
    private OnBoardTapListener listener;
    private boolean inputEnabled = true;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint terrPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cell;      // 每格像素
    private float margin;    // 棋盘边缘到第一条线的距离

    public GoBoardView(Context ctx) { super(ctx); init(); }
    public GoBoardView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        lineColor(lineColor());
    }

    private int boardBgColor = Color.parseColor("#E3B771");
    private int markerColor = Color.parseColor("#3D7AFD");

    /** 主题配色: 棋盘底色/线色/最后一手标记色 */
    public void setThemeColors(int bg, int line, int marker) {
        boardBgColor = bg;
        markerColor = marker;
        lineColor(line);
        invalidate();
    }

    private int lineColor() { return Color.rgb(60, 40, 20); }

    private void lineColor(int c) {
        linePaint.setColor(c);
        linePaint.setStrokeWidth(2f);
        textPaint.setColor(Color.parseColor("#4E5969"));
        textPaint.setTextSize(20f);
    }

    public void setOnBoardTapListener(OnBoardTapListener l) { listener = l; }
    public void setInputEnabled(boolean e) { inputEnabled = e; }

    public void setBoard(int[][] b, int lx, int ly) {
        board = b; lastX = lx; lastY = ly; territory = null; invalidate();
    }

    /** 棋盘尺寸(打谱可能为 9/13 路); 落子/铺满前期统一重置坐标记录 */
    public void setBoardSize(int n) {
        N = Math.max(2, Math.min(MAX_N, n));
        lastX = lastY = -1;
        invalidate();
    }

    public void showTerritory(int[][] t) { territory = t; invalidate(); }
    public void clearTerritory() { territory = null; invalidate(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        // 正方形; 屏幕高度不够时按剩余高度收缩, 避免底部按钮被挤出屏幕
        int s = (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED)
                ? w : Math.min(w, MeasureSpec.getSize(hSpec));
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        // 最外线尽量贴边: 边缘留白只需容纳最外侧棋子的半径(0.47*cell) + 2px 抗锯齿余量.
        // m = r + 2 = 0.47*(w-2m)/(N-1) + 2  =>  m = (0.47w/(N-1) + 2) / (1 + 0.94/(N-1))
        margin = (0.47f * w / (N - 1) + 2f) / (1f + 0.94f / (N - 1));
        cell = (w - 2 * margin) / (N - 1);

        // 棋盘底色
        cv.drawColor(boardBgColor);

        // 棋盘外描边: 复刻 iOS(1pt 线, 内缩 0.5pt, 线色 70% 不透明), 贴画布边缘
        float sw = dp(1f);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(sw);
        framePaint.setColor(linePaint.getColor());
        framePaint.setAlpha(179);
        float fi = sw / 2f + 1f;   // 再让 1px, 保证最外一列仍是木色, 不破坏左右贴屏
        cv.drawRect(fi, fi, w - fi, getHeight() - fi, framePaint);

        // 网格线
        for (int i = 0; i < N; i++) {
            float p = margin + i * cell;
            cv.drawLine(margin, p, w - margin, p, linePaint);
            cv.drawLine(p, margin, p, w - margin, linePaint);
        }
        // 边框加粗
        linePaint.setStrokeWidth(4f);
        cv.drawLine(margin, margin, w - margin, margin, linePaint);
        cv.drawLine(margin, w - margin, w - margin, w - margin, linePaint);
        cv.drawLine(margin, margin, margin, w - margin, linePaint);
        cv.drawLine(w - margin, margin, w - margin, w - margin, linePaint);
        linePaint.setStrokeWidth(2f);

        // 星位(按路数)
        int[] stars;
        if (N == 19) stars = new int[]{3, 9, 15};
        else if (N == 13) stars = new int[]{3, 6, 9};
        else if (N == 9) stars = new int[]{3, 5};
        else stars = new int[0];
        for (int sx : stars) for (int sy : stars) {
            float r = cell * 0.09f;
            cv.drawCircle(px(sx), py(sy), r, linePaint);
        }

        // 形势点
        if (territory != null) {
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                if (board[y][x] != GoGame.EMPTY) continue;
                int t = territory[y][x];
                if (t == GoGame.EMPTY) continue;
                terrPaint.setColor(t == GoGame.BLACK ? Color.argb(90, 0, 0, 0)
                                                     : Color.argb(140, 255, 255, 255));
                float s = cell * 0.28f;
                cv.drawRect(px(x) - s / 2, py(y) - s / 2, px(x) + s / 2, py(y) + s / 2, terrPaint);
            }
        }

        // 棋子
        float r = cell * 0.47f;
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int v = board[y][x];
            if (v == GoGame.EMPTY) continue;
            drawStone(cv, px(x), py(y), r, v == GoGame.BLACK);
        }

        // 最后落子标记
        if (lastX >= 0 && lastY >= 0 && lastX < N && lastY < N
                && board[lastY][lastX] != GoGame.EMPTY) {
            stonePaint.setShader(null);
            stonePaint.setStyle(Paint.Style.STROKE);
            stonePaint.setStrokeWidth(3f);
            stonePaint.setColor(board[lastY][lastX] == GoGame.BLACK
                    ? Color.WHITE : markerColor);
            cv.drawCircle(px(lastX), py(lastY), r * 0.5f, stonePaint);
            stonePaint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawStone(Canvas cv, float cx, float cy, float r, boolean black) {
        if (black) {
            stonePaint.setShader(new RadialGradient(cx - r / 3, cy - r / 3, r,
                    new int[]{Color.rgb(90, 90, 90), Color.rgb(20, 20, 20)}, null, Shader.TileMode.CLAMP));
        } else {
            stonePaint.setShader(new RadialGradient(cx - r / 3, cy - r / 3, r,
                    new int[]{Color.WHITE, Color.rgb(200, 200, 200)}, null, Shader.TileMode.CLAMP));
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
