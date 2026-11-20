package com.sijia.go;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 围棋棋局状态与规则: 落子/提子/打劫/悔棋/中国规则数子.
 * 坐标: (0,0) 为左上角, x 向右, y 向下, 范围 0..size-1.
 * 支持导入棋谱使用的 9/13/19 路等非标准尺寸.
 */
public class GoGame {
    public static final int EMPTY = 0, BLACK = 1, WHITE = 2;
    public static final int SIZE = 19;   // 默认尺寸, 实例尺寸用 size()

    public static class Move {
        public int x, y;          // -1,-1 表示 PASS
        public int color;
        public List<int[]> captured = new ArrayList<>();
        public int koX = -1, koY = -1;  // 落子前的劫点, 用于悔棋恢复
    }

    private final int size;
    private final int[][] board;
    private final Deque<Move> history = new ArrayDeque<>();
    private int koX = -1, koY = -1;
    private int turn = BLACK;
    private int handicap = 0;
    private double komi = 7.5;

    public GoGame() { this(SIZE); }

    public GoGame(int size) {
        this.size = Math.max(2, Math.min(25, size));
        this.board = new int[this.size][this.size];
    }

    public int size() { return size; }
    public int[][] board() { return board; }
    public int turn() { return turn; }
    public int moveCount() { return handicap + countStones(); }
    public void setKomi(double k) { komi = k; }
    public int koX() { return koX; }
    public int koY() { return koY; }

    private int countStones() {
        int n = 0;
        for (int[] row : board) for (int v : row) if (v != EMPTY) n++;
        return n;
    }

    public void clear() {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) board[y][x] = EMPTY;
        history.clear();
        koX = koY = -1;
        turn = BLACK;
        handicap = 0;
    }

    /** 摆让子(不改变先后手, 让子后白先) */
    public void placeHandicap(List<int[]> points) {
        for (int[] p : points) board[p[1]][p[0]] = BLACK;
        handicap = points.size();
        if (handicap > 0) turn = WHITE;
        history.clear();
        koX = koY = -1;
    }

    /** 棋谱摆子(AB/AW/AE): 黑/白固定子 + 清空点, 并指定行棋方 */
    public void setup(List<int[]> black, List<int[]> white, List<int[]> empty, int toMove) {
        clear();
        for (int[] p : empty) board[p[1]][p[0]] = EMPTY;
        if (black != null) for (int[] p : black) board[p[1]][p[0]] = BLACK;
        if (white != null) for (int[] p : white) board[p[1]][p[0]] = WHITE;
        handicap = black == null ? 0 : black.size();
        if (toMove == BLACK || toMove == WHITE) turn = toMove;
        else turn = handicap > 0 ? WHITE : BLACK;
    }

    public boolean isLegal(int x, int y, int color) {
        if (x < 0 || x >= size || y < 0 || y >= size) return false;
        if (board[y][x] != EMPTY) return false;
        if (x == koX && y == koY) return false;
        return hasLibertyAfterPlay(x, y, color);
    }

    private boolean hasLibertyAfterPlay(int x, int y, int color) {
        int enemy = 3 - color;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (in(nx, ny) && board[ny][nx] == enemy) {
                if (liberties(nx, ny, new HashSet<>()) == 1) return true; // 可提
            }
        }
        board[y][x] = color;
        int lib = liberties(x, y, new HashSet<>());
        board[y][x] = EMPTY;
        return lib > 0;
    }

    /** 当前方落子. 返回 null 表示非法手 */
    public Move play(int x, int y) {
        if (!isLegal(x, y, turn)) return null;
        Move m = new Move();
        m.x = x; m.y = y; m.color = turn;
        m.koX = koX; m.koY = koY;
        board[y][x] = turn;
        int enemy = 3 - turn;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (in(nx, ny) && board[ny][nx] == enemy && liberties(nx, ny, new HashSet<>()) == 0) {
                capture(nx, ny, enemy, m.captured);
            }
        }
        // 单劫检测: 只提一子且自身也只有一气时的提子点
        koX = koY = -1;
        if (m.captured.size() == 1 && liberties(x, y, new HashSet<>()) == 1
                && groupSize(x, y) == 1) {
            koX = m.captured.get(0)[0];
            koY = m.captured.get(0)[1];
        }
        history.push(m);
        turn = 3 - turn;
        return m;
    }

    public Move pass() {
        Move m = new Move();
        m.x = -1; m.y = -1; m.color = turn;
        m.koX = koX; m.koY = koY;
        koX = koY = -1;
        history.push(m);
        turn = 3 - turn;
        return m;
    }

    /** 悔一手, 返回被悔的着法(无着可悔返回 null) */
    public Move undo() {
        Move m = history.poll();
        if (m == null) return null;
        koX = m.koX; koY = m.koY;
        if (m.x >= 0) {
            board[m.y][m.x] = EMPTY;
            for (int[] c : m.captured) board[c[1]][c[0]] = 3 - m.color;
        }
        turn = m.color;
        return m;
    }

    public boolean canUndo() { return !history.isEmpty(); }

    public List<Move> history() { return new ArrayList<>(history); }

    /** 中国规则数子: 返回 {黑得分, 白得分}(子空皆地, 不含贴目) */
    public float[] chineseScore() {
        int[][] owner = new int[size][size]; // 每点归属
        for (int y = 0; y < size; y++) System.arraycopy(board[y], 0, owner[y], 0, size);
        boolean[][] vis = new boolean[size][size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (board[y][x] != EMPTY || vis[y][x]) continue;
            // flood-fill 一片空, 看邻接颜色
            List<int[]> region = new ArrayList<>();
            Set<Integer> adj = new HashSet<>();
            Deque<int[]> q = new ArrayDeque<>();
            q.add(new int[]{x, y});
            vis[y][x] = true;
            while (!q.isEmpty()) {
                int[] p = q.poll();
                region.add(p);
                for (int[] d : DIRS) {
                    int nx = p[0] + d[0], ny = p[1] + d[1];
                    if (!in(nx, ny)) continue;
                    if (board[ny][nx] == EMPTY) {
                        if (!vis[ny][nx]) { vis[ny][nx] = true; q.add(new int[]{nx, ny}); }
                    } else adj.add(board[ny][nx]);
                }
            }
            if (adj.size() == 1) {
                int c = adj.iterator().next();
                for (int[] p : region) owner[p[1]][p[0]] = c;
            }
        }
        float b = 0, w = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (owner[y][x] == BLACK) b++;
            else if (owner[y][x] == WHITE) w++;
        }
        return new float[]{b, w};
    }

    public double komi() { return komi; }

    // ---------------- 内部 ----------------
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};

    private boolean in(int x, int y) { return x >= 0 && x < size && y >= 0 && y < size; }

    private int liberties(int x, int y, Set<Long> seen) {
        int color = board[y][x];
        if (color == EMPTY) return 0;
        int libs = 0;
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{x, y});
        seen.add(key(x, y));
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int[] d : DIRS) {
                int nx = p[0] + d[0], ny = p[1] + d[1];
                if (!in(nx, ny)) continue;
                if (board[ny][nx] == EMPTY) libs++;
                else if (board[ny][nx] == color && seen.add(key(nx, ny))) q.add(new int[]{nx, ny});
            }
        }
        return libs;
    }

    private int groupSize(int x, int y) {
        int color = board[y][x];
        Set<Long> seen = new HashSet<>();
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{x, y});
        seen.add(key(x, y));
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int[] d : DIRS) {
                int nx = p[0] + d[0], ny = p[1] + d[1];
                if (in(nx, ny) && board[ny][nx] == color && seen.add(key(nx, ny)))
                    q.add(new int[]{nx, ny});
            }
        }
        return seen.size();
    }

    private void capture(int x, int y, int color, List<int[]> out) {
        Deque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(new int[]{x, y});
        seen.add(key(x, y));
        while (!q.isEmpty()) {
            int[] p = q.poll();
            board[p[1]][p[0]] = EMPTY;
            out.add(p);
            for (int[] d : DIRS) {
                int nx = p[0] + d[0], ny = p[1] + d[1];
                if (in(nx, ny) && board[ny][nx] == color && seen.add(key(nx, ny)))
                    q.add(new int[]{nx, ny});
            }
        }
    }

    private long key(int x, int y) { return (long) y * size + x; }

    // ---------------- 坐标转换 ----------------
    private static final String GTP_COLS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";

    /** (x,y) -> GTP 顶点, 如 19 路 (3,3) -> "Q16" */
    public String toGtp(int x, int y) {
        return GTP_COLS.charAt(x) + String.valueOf(size - y);
    }

    /** GTP 顶点 -> (x,y), "PASS"/"RESIGN" 返回 null */
    public int[] fromGtp(String v) {
        v = v.trim().toUpperCase();
        if (v.equals("PASS") || v.equals("RESIGN")) return null;
        int x = GTP_COLS.indexOf(v.charAt(0));
        String num = v.substring(1).replaceAll("[^0-9]", "");
        if (num.isEmpty()) return null;
        int y = size - Integer.parseInt(num);
        if (x < 0 || y < 0 || y >= size) return null;
        return new int[]{x, y};
    }

    /** (x,y) -> SGF 坐标, 如 (3,15) -> "dp" */
    public static String toSgf(int x, int y) {
        return "" + (char)('a' + x) + (char)('a' + y);
    }

    public static int[] fromSgf(String s) {
        return new int[]{s.charAt(0) - 'a', s.charAt(1) - 'a'};
    }

    /** GTP 让子返回的顶点列表 -> 坐标数组 */
    public List<int[]> parseVertices(String s) {
        List<int[]> out = new ArrayList<>();
        for (String v : s.trim().split("\\s+")) {
            int[] p = fromGtp(v);
            if (p != null) out.add(p);
        }
        return out;
    }
}
