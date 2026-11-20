package com.sijia.go;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 自研五子棋 AI：棋型评分 + 威胁点搜索（成五/堵五/双威胁/两层极小化极大）。
 * 无第三方引擎，纯 Java 计算，可在后台线程调用。
 */
public final class GomokuAi {
    public static final int MAX_LEVEL = 5;

    /** 落点过滤（禁手模式下排除黑棋禁手点）. */
    public interface MoveFilter { boolean allow(int x, int y); }

    private static final int EMPTY = 0;
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
    private static final Random RND = new Random();

    private GomokuAi() {}

    public static String levelName(int level) {
        switch (Math.max(1, Math.min(MAX_LEVEL, level))) {
            case 1: return "入门";
            case 2: return "初级";
            case 3: return "中级";
            case 4: return "高级";
            default: return "大师";
        }
    }

    /** 返回 {x,y}; 棋盘已满时返回 null. */
    public static int[] choose(int[][] b, int me, int level) {
        return choose(b, me, level, null);
    }

    /** 带落点过滤的版本：filter 返回 false 的点不会作为己方落点（用于禁手）. */
    public static int[] choose(int[][] b, int me, int level, MoveFilter filter) {
        int n = b.length;
        if (n <= 0) return null;
        int center = n / 2;
        if (countStones(b) == 0) return new int[]{center, center};
        int opp = 3 - me;
        List<int[]> cand = candidates(b, 2);
        if (filter != null) {
            List<int[]> kept = new ArrayList<>(cand.size());
            for (int[] c : cand) if (filter.allow(c[0], c[1])) kept.add(c);
            cand = kept;
        }
        if (cand.isEmpty()) return findEmpty(b);

        // 1. 自己能成五：直接赢
        for (int[] c : cand) if (isWinMove(b, c[0], c[1], me)) return c;
        // 2. 对手下一步能成五：必须堵
        for (int[] c : cand) if (isWinMove(b, c[0], c[1], opp)) return c;

        List<Move> scored = new ArrayList<>(cand.size());
        for (int[] c : cand) {
            long attack = scorePoint(b, c[0], c[1], me);
            long defend = scorePoint(b, c[0], c[1], opp);
            scored.add(new Move(c[0], c[1], attack, defend,
                    attack + defend * 9 / 10));
        }
        scored.sort((a, b1) -> Long.compare(b1.total, a.total));

        if (level <= 1) {
            // 入门：在最优的三个点里随机，避免每局一模一样
            int k = Math.min(3, scored.size());
            return scored.get(RND.nextInt(k)).xy();
        }
        if (level == 2) {
            // 初级：贪心，防守权重偏低（会漏防活三，弱于中级）
            Move best = scored.get(0);
            long bestV = Long.MIN_VALUE;
            for (Move m : scored) {
                long v = m.attack + m.defend * 4 / 10;
                if (v > bestV) {
                    bestV = v;
                    best = m;
                }
            }
            return best.xy();
        }

        // 3. 自己下一手能成活四：抢先做活四（对方只能堵一头，随后成五）
        for (int[] c : cand) if (openFourMove(b, c[0], c[1], me)) return c;

        // 4. 对手下一手能成活四（即我方面临活三）：必须挡在关键点
        List<int[]> keyBlocks = new ArrayList<>();
        for (int[] c : cand) if (openFourMove(b, c[0], c[1], opp)) keyBlocks.add(c);
        if (!keyBlocks.isEmpty()) {
            int[] bestBlock = null;
            long bestBlockValue = Long.MIN_VALUE;
            for (int[] c : keyBlocks) {
                long v = scorePoint(b, c[0], c[1], me) + scorePoint(b, c[0], c[1], opp);
                if (v > bestBlockValue) {
                    bestBlockValue = v;
                    bestBlock = c;
                }
            }
            if (bestBlock != null) return bestBlock;
        }

        // 中级：贪心（防守权重 0.9）+ 双威胁（双活三/四三/双四）
        if (level == 3) {
            int top = Math.min(10, scored.size());
            for (int i = 0; i < top; i++) {
                Move m = scored.get(i);
                b[m.y][m.x] = me;
                boolean fork = countWinMoves(b, me, 2) >= 2;
                b[m.y][m.x] = EMPTY;
                if (fork) return m.xy();
            }
            return scored.get(0).xy();
        }

        // 高级搜索前 6 个候选, 大师搜索前 14 个（更不易漏掉反击手段）
        int topK = Math.min(level >= 5 ? 14 : 6, scored.size());
        long bestValue = Long.MIN_VALUE;
        int[] best = null;
        for (int i = 0; i < topK; i++) {
            Move m = scored.get(i);
            b[m.y][m.x] = me;
            long value;

            // 双威胁（双活三/四三/双四）：对手无法同时防住，视为必胜
            int myThreats = countWinMoves(b, me, 2);
            if (level >= 5 && myThreats >= 2) {
                value = 900_000_000L + m.total;
            } else {
                List<int[]> oc = candidates(b, 2);
                long oppBest = 0;
                boolean oppWin = false;
                int[] oppMove = null;
                for (int[] o : oc) {
                    if (isWinMove(b, o[0], o[1], opp)) {
                        oppWin = true;
                        break;
                    }
                    long s = scorePoint(b, o[0], o[1], opp);
                    if (s > oppBest) {
                        oppBest = s;
                        oppMove = o;
                    }
                }
                if (oppWin) {
                    // 这一手给了对手成五机会，基本不能走
                    value = Long.MIN_VALUE / 4 + m.total;
                } else {
                    value = m.total - oppBest * 9 / 10;
                    // 单一威胁（做出一个四）：大师更愿意抢先手
                    if (myThreats == 1) value += level >= 5 ? 4_000_000L : 150_000L;
                    // 大师：对手最佳应手若直接形成活四（四三/双四同理），挡不住
                    if (level >= 5 && oppMove != null
                            && openFourMove(b, oppMove[0], oppMove[1], opp)) {
                        value = Long.MIN_VALUE / 8 + m.total;
                    }
                }
            }
            b[m.y][m.x] = EMPTY;
            if (value > bestValue) {
                bestValue = value;
                best = m.xy();
            }
        }
        return best != null ? best : scored.get(0).xy();
    }

    // ---------------- 棋型评分 ----------------

    /** 假设在 (x,y) 落 color 子后的局部价值. 调用前该点应为空. */
    private static long scorePoint(int[][] b, int x, int y, int color) {
        int n = b.length;
        int opp = 3 - color;
        long total = 0;
        for (int[] d : DIRS) {
            int dx = d[0], dy = d[1];
            int count = 1;
            int open = 0;

            int cx = x + dx, cy = y + dy;
            while (in(n, cx, cy) && b[cy][cx] == color) {
                count++;
                cx += dx;
                cy += dy;
            }
            if (in(n, cx, cy) && b[cy][cx] == EMPTY) open++;

            cx = x - dx;
            cy = y - dy;
            while (in(n, cx, cy) && b[cy][cx] == color) {
                count++;
                cx -= dx;
                cy -= dy;
            }
            if (in(n, cx, cy) && b[cy][cx] == EMPTY) open++;

            total += contiguousScore(count, open);

            // 五格窗口：把断点棋型（如 11011）也计入
            for (int s = -4; s <= 0; s++) {
                boolean ok = true;
                int cnt = 0;
                for (int k = 0; k < 5; k++) {
                    int ox = s + k;
                    int nx = x + dx * ox;
                    int ny = y + dy * ox;
                    if (!in(n, nx, ny)) {
                        ok = false;
                        break;
                    }
                    int v = ox == 0 ? color : b[ny][nx];
                    if (v == opp) {
                        ok = false;
                        break;
                    }
                    if (v == color) cnt++;
                }
                if (ok) total += windowScore(cnt);
            }
        }
        return total;
    }

    private static long contiguousScore(int count, int open) {
        if (count >= 5) return 50_000_000L;
        if (count == 4) return open == 2 ? 5_000_000L : (open == 1 ? 400_000L : 0);
        if (count == 3) return open == 2 ? 300_000L : (open == 1 ? 20_000L : 0);
        if (count == 2) return open == 2 ? 8_000L : (open == 1 ? 1_000L : 0);
        return open == 2 ? 200L : 0;
    }

    private static long windowScore(int count) {
        switch (count) {
            case 5: return 5_000_000L;
            case 4: return 50_000L;
            case 3: return 4_000L;
            case 2: return 400L;
            default: return 20L;
        }
    }

    // ---------------- 搜索辅助 ----------------

    private static int countWinMoves(int[][] b, int color, int limit) {
        int n = 0;
        for (int[] c : candidates(b, 2)) {
            if (isWinMove(b, c[0], c[1], color)) {
                n++;
                if (n >= limit) break;
            }
        }
        return n;
    }

    /** 该点落 color 后是否在某个方向形成活四（连续 4 子且两端皆空）. */
    private static boolean openFourMove(int[][] b, int x, int y, int color) {
        if (b[y][x] != EMPTY) return false;
        b[y][x] = color;
        boolean open = false;
        for (int[] d : DIRS) {
            if (openFourThrough(b, x, y, d[0], d[1], color)) {
                open = true;
                break;
            }
        }
        b[y][x] = EMPTY;
        return open;
    }

    private static boolean openFourThrough(int[][] b, int x, int y, int dx, int dy, int color) {
        int n = b.length;
        if (runThrough(b, x, y, dx, dy, color) != 4) return false;
        int x1 = x, y1 = y, x2 = x, y2 = y;
        while (in(n, x1 - dx, y1 - dy) && b[y1 - dy][x1 - dx] == color) {
            x1 -= dx;
            y1 -= dy;
        }
        while (in(n, x2 + dx, y2 + dy) && b[y2 + dy][x2 + dx] == color) {
            x2 += dx;
            y2 += dy;
        }
        return in(n, x1 - dx, y1 - dy) && b[y1 - dy][x1 - dx] == EMPTY
                && in(n, x2 + dx, y2 + dy) && b[y2 + dy][x2 + dx] == EMPTY;
    }

    private static int runThrough(int[][] b, int x, int y, int dx, int dy, int color) {
        int n = b.length;
        int cnt = 1;
        int cx = x + dx, cy = y + dy;
        while (in(n, cx, cy) && b[cy][cx] == color) {
            cnt++;
            cx += dx;
            cy += dy;
        }
        cx = x - dx;
        cy = y - dy;
        while (in(n, cx, cy) && b[cy][cx] == color) {
            cnt++;
            cx -= dx;
            cy -= dy;
        }
        return cnt;
    }

    private static boolean isWinMove(int[][] b, int x, int y, int color) {
        int n = b.length;
        if (!in(n, x, y) || b[y][x] != EMPTY) return false;
        b[y][x] = color;
        boolean win = false;
        for (int[] d : DIRS) {
            int cnt = 1;
            int cx = x + d[0], cy = y + d[1];
            while (in(n, cx, cy) && b[cy][cx] == color) {
                cnt++;
                cx += d[0];
                cy += d[1];
            }
            cx = x - d[0];
            cy = y - d[1];
            while (in(n, cx, cy) && b[cy][cx] == color) {
                cnt++;
                cx -= d[0];
                cy -= d[1];
            }
            if (cnt >= 5) {
                win = true;
                break;
            }
        }
        b[y][x] = EMPTY;
        return win;
    }

    /** 已有棋子附近 dist 格内的空点；棋盘为空时返回全部空点. */
    private static List<int[]> candidates(int[][] b, int dist) {
        int n = b.length;
        boolean[][] mark = new boolean[n][n];
        boolean any = false;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (b[y][x] == EMPTY) continue;
                any = true;
                for (int dy = -dist; dy <= dist; dy++) {
                    for (int dx = -dist; dx <= dist; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (in(n, nx, ny) && b[ny][nx] == EMPTY) mark[ny][nx] = true;
                    }
                }
            }
        }
        List<int[]> out = new ArrayList<>();
        if (!any) {
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) out.add(new int[]{x, y});
            return out;
        }
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (mark[y][x]) out.add(new int[]{x, y});
        return out;
    }

    private static int[] findEmpty(int[][] b) {
        for (int y = 0; y < b.length; y++)
            for (int x = 0; x < b.length; x++)
                if (b[y][x] == EMPTY) return new int[]{x, y};
        return null;
    }

    private static int countStones(int[][] b) {
        int n = 0;
        for (int[] row : b)
            for (int v : row)
                if (v != EMPTY) n++;
        return n;
    }

    private static boolean in(int n, int x, int y) {
        return x >= 0 && y >= 0 && x < n && y < n;
    }

    private static final class Move {
        final int x, y;
        final long attack, defend, total;

        Move(int x, int y, long attack, long defend, long total) {
            this.x = x;
            this.y = y;
            this.attack = attack;
            this.defend = defend;
            this.total = total;
        }

        int[] xy() { return new int[]{x, y}; }
    }
}
