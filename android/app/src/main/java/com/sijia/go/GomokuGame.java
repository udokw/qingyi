package com.sijia.go;

import java.util.ArrayList;
import java.util.List;

/**
 * 五子棋棋局状态与规则（默认无禁手 freestyle）。
 * 坐标: (0,0) 左上, x 向右, y 向下.
 * 黑先, 任意一方在横/竖/斜方向连成 5 子及以上即胜.
 */
public class GomokuGame {
    public static final int EMPTY = 0, BLACK = 1, WHITE = 2;
    public static final int SIZE = 19;

    /** tryPlay 返回值. */
    public static final int PLAY_OK = 0;
    public static final int PLAY_OCCUPIED = 1;
    public static final int PLAY_OVER = 2;
    public static final int PLAY_FORBIDDEN = 3;

    /** 禁手类型. */
    public static final int FORBID_NONE = 0;
    public static final int FORBID_OVERLINE = 1;
    public static final int FORBID_DOUBLE_FOUR = 2;
    public static final int FORBID_DOUBLE_THREE = 3;

    private final int size;
    private final int[][] board;
    private final List<int[]> history = new ArrayList<>(); // {color, x, y}
    private int turn = BLACK;
    private int winner = EMPTY;
    /** 连珠禁手（仅黑棋）：长连 / 四四 / 三三. 默认关闭 = freestyle. */
    private boolean renju = false;
    /** 获胜五连的首尾坐标 {x1,y1,x2,y2}, 未结束为 null. */
    private int[] winLine;
    private int lastX = -1, lastY = -1;

    public GomokuGame() { this(SIZE); }

    public GomokuGame(int size) {
        this.size = Math.max(5, Math.min(25, size));
        this.board = new int[this.size][this.size];
    }

    public int size() { return size; }
    public int[][] board() { return board; }
    public boolean isRenju() { return renju; }
    public void setRenju(boolean on) { renju = on; }
    public int turn() { return turn; }
    public int winner() { return winner; }
    public int[] winLine() { return winLine; }
    public int lastX() { return lastX; }
    public int lastY() { return lastY; }
    public int moveCount() { return history.size(); }
    public boolean canUndo() { return !history.isEmpty(); }
    public boolean isOver() { return winner != EMPTY; }
    public List<int[]> history() { return history; }

    public int get(int x, int y) {
        if (x < 0 || y < 0 || x >= size || y >= size) return EMPTY;
        return board[y][x];
    }

    public boolean isEmpty(int x, int y) { return get(x, y) == EMPTY; }

    public void clear() {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) board[y][x] = EMPTY;
        history.clear();
        turn = BLACK;
        winner = EMPTY;
        winLine = null;
        lastX = lastY = -1;
    }

    /** 当前方落子; 非法或已结束返回 false. */
    public boolean play(int x, int y) {
        return tryPlay(x, y) == PLAY_OK;
    }

    /**
     * 带原因码的落子. 禁手模式(renju)下黑棋落禁手点返回 PLAY_FORBIDDEN 且棋局不变.
     * 成五优先: 同一手若已形成五连, 即使别处同时出现长连也判胜, 不算禁手.
     */
    public int tryPlay(int x, int y) {
        if (winner != EMPTY) return PLAY_OVER;
        if (x < 0 || y < 0 || x >= size || y >= size) return PLAY_OCCUPIED;
        if (board[y][x] != EMPTY) return PLAY_OCCUPIED;
        int color = turn;
        if (renju && color == BLACK && forbiddenAt(board, x, y) != FORBID_NONE)
            return PLAY_FORBIDDEN;
        board[y][x] = color;
        history.add(new int[]{color, x, y});
        lastX = x;
        lastY = y;
        int[] line = findWin(x, y, color);
        if (line != null) {
            winner = color;
            winLine = line;
        } else {
            turn = 3 - color;
        }
        return PLAY_OK;
    }

    /** 悔一手; 返回是否有子可悔. */
    public boolean undo() {
        if (history.isEmpty()) return false;
        int[] last = history.remove(history.size() - 1);
        board[last[2]][last[1]] = EMPTY;
        turn = last[0];
        winner = EMPTY;
        winLine = null;
        if (history.isEmpty()) {
            lastX = lastY = -1;
        } else {
            int[] prev = history.get(history.size() - 1);
            lastX = prev[1];
            lastY = prev[2];
        }
        return true;
    }

    public boolean isFull() {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (board[y][x] == EMPTY) return false;
        return true;
    }

    /** 以 (x,y) 落子后是否五连; 不修改棋局. */
    public boolean isWinningMove(int x, int y, int color) {
        if (x < 0 || y < 0 || x >= size || y >= size) return false;
        if (board[y][x] != EMPTY) return false;
        return findWin(x, y, color) != null;
    }

    private static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    /** 返回包含 (x,y) 的五连首尾坐标, 没有则 null. */
    private int[] findWin(int x, int y, int color) {
        for (int[] d : DIRS) {
            int x1 = x, y1 = y, x2 = x, y2 = y;
            while (in(x1 - d[0], y1 - d[1]) && board[y1 - d[1]][x1 - d[0]] == color) {
                x1 -= d[0];
                y1 -= d[1];
            }
            while (in(x2 + d[0], y2 + d[1]) && board[y2 + d[1]][x2 + d[0]] == color) {
                x2 += d[0];
                y2 += d[1];
            }
            int count = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) + 1;
            if (count >= 5) return new int[]{x1, y1, x2, y2};
        }
        return null;
    }

    private boolean in(int x, int y) { return x >= 0 && y >= 0 && x < size && y < size; }

    // ---------------- 连珠禁手（黑棋） ----------------

    /** 当前棋盘上黑棋落 (x,y) 是否禁手; 未开启禁手时恒为 false. */
    public boolean isForbidden(int x, int y) {
        return renju && forbiddenAt(board, x, y) != FORBID_NONE;
    }

    /** 任意棋盘副本上黑棋落 (x,y) 是否禁手（AI 计算时用, 不改动原棋局）. */
    public static boolean isForbiddenPoint(int[][] b, int x, int y) {
        return forbiddenAt(b, x, y) != FORBID_NONE;
    }

    /** 禁手类型文案; 非禁手返回空串. */
    public String forbiddenText(int x, int y) {
        switch (forbiddenAt(board, x, y)) {
            case FORBID_OVERLINE:
                return "长连禁手（黑棋不能连成六子及以上）";
            case FORBID_DOUBLE_FOUR:
                return "四四禁手（黑棋不能同时形成两个四）";
            case FORBID_DOUBLE_THREE:
                return "三三禁手（黑棋不能同时形成两个活三）";
            default:
                return "";
        }
    }

    /**
     * 标准连珠禁手判定（只针对黑棋）:
     * 长连（同方向连续 >=6）; 四四（两个方向各能成五）; 三三（两个方向各能成活四）.
     * 若该手本身已成五, 判为合法胜着.
     */
    private static int forbiddenAt(int[][] bd, int x, int y) {
        int n = bd.length;
        if (x < 0 || y < 0 || x >= n || y >= n) return FORBID_NONE;
        if (bd[y][x] != EMPTY) return FORBID_NONE;
        bd[y][x] = BLACK;
        try {
            boolean five = false, overline = false;
            int fours = 0, threes = 0;
            for (int[] d : DIRS) {
                int cnt = runThrough(bd, x, y, d[0], d[1], BLACK);
                if (cnt == 5) five = true;
                else if (cnt > 5) overline = true;
                if (dirHasFour(bd, x, y, d[0], d[1])) fours++;
                else if (dirHasOpenThree(bd, x, y, d[0], d[1])) threes++;
            }
            if (five) return FORBID_NONE;
            if (overline) return FORBID_OVERLINE;
            if (fours >= 2) return FORBID_DOUBLE_FOUR;
            if (threes >= 2) return FORBID_DOUBLE_THREE;
            return FORBID_NONE;
        } finally {
            bd[y][x] = EMPTY;
        }
    }

    /** 以 (x,y) 为中心, 指定方向上的连续同色子数（含自身）. */
    private static int runThrough(int[][] bd, int x, int y, int dx, int dy, int color) {
        int n = bd.length;
        int cnt = 1;
        int cx = x + dx, cy = y + dy;
        while (cx >= 0 && cy >= 0 && cx < n && cy < n && bd[cy][cx] == color) {
            cnt++;
            cx += dx;
            cy += dy;
        }
        cx = x - dx;
        cy = y - dy;
        while (cx >= 0 && cy >= 0 && cx < n && cy < n && bd[cy][cx] == color) {
            cnt++;
            cx -= dx;
            cy -= dy;
        }
        return cnt;
    }

    /** 该方向是否存在一个空点, 补上黑子后能成五（即已构成一个"四"）. */
    private static boolean dirHasFour(int[][] bd, int x, int y, int dx, int dy) {
        int n = bd.length;
        for (int s = -4; s <= 4; s++) {
            if (s == 0) continue;
            int qx = x + dx * s, qy = y + dy * s;
            if (qx < 0 || qy < 0 || qx >= n || qy >= n || bd[qy][qx] != EMPTY) continue;
            bd[qy][qx] = BLACK;
            boolean four = runThrough(bd, x, y, dx, dy, BLACK) == 5;
            bd[qy][qx] = EMPTY;
            if (four) return true;
        }
        return false;
    }

    /** 该方向是否存在一个空点, 补上后能形成"活四"（即当前是活三）. */
    private static boolean dirHasOpenThree(int[][] bd, int x, int y, int dx, int dy) {
        int n = bd.length;
        for (int s = -4; s <= 4; s++) {
            if (s == 0) continue;
            int qx = x + dx * s, qy = y + dy * s;
            if (qx < 0 || qy < 0 || qx >= n || qy >= n || bd[qy][qx] != EMPTY) continue;
            bd[qy][qx] = BLACK;
            boolean three = openFourThrough(bd, x, y, dx, dy);
            bd[qy][qx] = EMPTY;
            if (three) return true;
        }
        return false;
    }

    /** (x,y) 所在方向是否恰好是"活四"：连续 4 子且两端皆空. */
    private static boolean openFourThrough(int[][] bd, int x, int y, int dx, int dy) {
        if (runThrough(bd, x, y, dx, dy, BLACK) != 4) return false;
        int n = bd.length;
        int x1 = x, y1 = y, x2 = x, y2 = y;
        while (x1 - dx >= 0 && y1 - dy >= 0 && x1 - dx < n && y1 - dy < n
                && bd[y1 - dy][x1 - dx] == BLACK) {
            x1 -= dx;
            y1 -= dy;
        }
        while (x2 + dx >= 0 && y2 + dy >= 0 && x2 + dx < n && y2 + dy < n
                && bd[y2 + dy][x2 + dx] == BLACK) {
            x2 += dx;
            y2 += dy;
        }
        return x1 - dx >= 0 && y1 - dy >= 0 && x1 - dx < n && y1 - dy < n
                && bd[y1 - dy][x1 - dx] == EMPTY
                && x2 + dx >= 0 && y2 + dy >= 0 && x2 + dx < n && y2 + dy < n
                && bd[y2 + dy][x2 + dx] == EMPTY;
    }

    /** AI 用的棋盘副本. */
    public int[][] copyBoard() {
        int[][] b = new int[size][size];
        for (int y = 0; y < size; y++) System.arraycopy(board[y], 0, b[y], 0, size);
        return b;
    }
}
