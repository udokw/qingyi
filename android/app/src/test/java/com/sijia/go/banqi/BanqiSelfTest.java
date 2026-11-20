package com.sijia.go.banqi;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 暗棋规则引擎 / AI 自测。
 *
 * <p>纯 Java main，不依赖 JUnit 与 Android；可用 javac 直接编译运行：
 * <pre>
 * javac -encoding UTF-8 -d out app/src/main/java/com/sijia/go/banqi/*.java \
 *       app/src/test/java/com/sijia/go/banqi/BanqiSelfTest.java
 * java -cp out com.sijia.go.banqi.BanqiSelfTest
 * </pre></p>
 */
public final class BanqiSelfTest {

    private static int checks;
    private static int failures;

    private BanqiSelfTest() {
    }

    public static void main(String[] args) {
        testCaptureMatrix();
        testInitialPosition();
        testFirstFlipAssignsColor();
        testUndoFlip();
        testCaptureRules();
        testMovementRules();
        testCannonRules();
        testWinByCapture();
        testStalemateLoses();
        testIdleDraw();
        testRepetitionDraw();
        testFenRoundTrip();
        testAiSelfPlay();
        testAiDoesNotUseHiddenIdentity();
        testRandomPlayouts();

        System.out.println();
        System.out.println("checks=" + checks + ", failures=" + failures);
        if (failures > 0) {
            throw new IllegalStateException("Banqi self-test failed: " + failures);
        }
        System.out.println("ALL PASS");
    }

    // ------------------------------------------------------------------
    // 测试用例
    // ------------------------------------------------------------------

    private static void testCaptureMatrix() {
        int[] types = {BanqiGame.KING, BanqiGame.ADVISOR, BanqiGame.ELEPHANT,
                BanqiGame.ROOK, BanqiGame.HORSE, BanqiGame.CANNON, BanqiGame.PAWN};
        // 行=攻击方，列=被吃方
        boolean[][] expected = {
                //  將     士     象     車     馬     炮     卒
                {true, true, true, true, true, true, false},
                {false, true, true, true, true, true, true},
                {false, false, true, true, true, true, true},
                {false, false, false, true, true, true, true},
                {false, false, false, false, true, true, true},
                {false, false, false, false, false, false, false},
                {true, false, false, false, false, false, true}
        };
        for (int a = 0; a < types.length; a++) {
            for (int d = 0; d < types.length; d++) {
                boolean actual = BanqiGame.canCaptureByStep(
                        BanqiGame.piece(BanqiGame.RED, types[a]),
                        BanqiGame.piece(BanqiGame.BLACK, types[d]));
                check(actual == expected[a][d], "吃子矩阵 " + types[a] + "->" + types[d]);
            }
        }
    }

    private static void testInitialPosition() {
        BanqiGame g = new BanqiGame(20261108L);
        int occupied = 0;
        int hidden = 0;
        for (int y = 0; y < BanqiGame.ROWS; y++) {
            for (int x = 0; x < BanqiGame.COLS; x++) {
                if (g.hasPieceAt(x, y)) {
                    occupied++;
                }
                if (g.isHiddenAt(x, y)) {
                    hidden++;
                }
                check(!g.isFaceUp(x, y), "开局全部背面 " + BanqiGame.coord(x, y));
            }
        }
        checkEq(occupied, 32, "开局 32 格全满");
        checkEq(hidden, 32, "开局 32 枚暗子");
        checkEq(g.legalActions().size(), 32, "开局只有 32 种翻子");
        checkEq(g.legalMovesFrom(0, 0).size(), 0, "开局无走子");
        checkEq(g.turnSeat(), BanqiGame.SEAT_FIRST, "座位 1 先手");
        checkEq(g.colorOfSeat(1), 0, "首翻前座位 1 颜色未定");
        checkEq(g.colorOfSeat(2), 0, "首翻前座位 2 颜色未定");
        checkEq(g.result(), BanqiGame.RESULT_NONE, "开局未结束");
        int[] pool = g.unseenPool();
        check(pool != null, "单机模式暗子池可读");
        if (pool != null) {
            int total = 0;
            for (int p = 1; p < pool.length; p++) {
                total += pool[p];
            }
            checkEq(total, 32, "暗子池共 32 枚");
        }
    }

    private static void testFirstFlipAssignsColor() {
        BanqiGame g = new BanqiGame(7L);
        BanqiGame.Move m = g.legalFlips().get(5);
        int revealed = g.pieceCodeAt(m.x, m.y);
        check(g.flip(m.x, m.y), "首翻成功");
        check(g.isFaceUp(m.x, m.y), "翻子后为明子");
        checkEq(g.pieceCodeAt(m.x, m.y), revealed, "翻子身份不变");
        checkEq(g.colorOfSeat(1), BanqiGame.colorOf(revealed), "先手取得翻出颜色");
        checkEq(g.colorOfSeat(2), 3 - BanqiGame.colorOf(revealed), "后手为另一色");
        checkEq(g.turnSeat(), BanqiGame.SEAT_SECOND, "翻子后轮到后手");
        checkEq(g.idlePlies(), 0, "翻子重置无进展计数");
        checkEq(g.historySnapshot().size(), 1, "历史一步");
    }

    private static void testUndoFlip() {
        BanqiGame g = new BanqiGame(11L);
        BanqiGame.Move m = g.legalFlips().get(0);
        String before = g.fen();
        check(g.flip(m.x, m.y), "翻子成功");
        check(g.undo() != null, "可撤销翻子");
        checkEq(g.fen(), before, "撤销后 FEN 完全一致");
        checkEq(g.turnSeat(), BanqiGame.SEAT_FIRST, "撤销后回到先手");
        checkEq(g.colorOfSeat(1), 0, "撤销后颜色恢复未定");
        checkEq(g.colorOfSeat(2), 0, "撤销后后手颜色恢复未定");
        checkEq(g.historySnapshot().size(), 0, "撤销后无历史");
        check(!g.isFaceUp(m.x, m.y), "撤销后恢复暗子");
    }

    private static void testCaptureRules() {
        BanqiGame g = new BanqiGame(1L);

        // 將不能吃卒
        Pos p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.KING, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.PAWN, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(!hasMove(g.legalMovesFrom(0, 0), 1, 0), "將不能吃卒");

        // 卒能吃將
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.PAWN, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.KING, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(hasMove(g.legalMovesFrom(0, 0), 1, 0), "卒能吃將");

        // 同级可互吃（將吃將）
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.KING, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.KING, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(hasMove(g.legalMovesFrom(0, 0), 1, 0), "同级可互吃");

        // 炮不能走步吃子（即使旁边是卒）
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.PAWN, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(!hasMove(g.legalMovesFrom(0, 0), 1, 0), "炮不能走步吃卒");

        // 車可吃炮
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.ROOK, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.CANNON, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(hasMove(g.legalMovesFrom(0, 0), 1, 0), "車可吃炮");
    }

    private static void testMovementRules() {
        BanqiGame g = new BanqiGame(1L);
        Pos p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.HORSE, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        List<BanqiGame.Move> moves = g.legalMovesFrom(0, 0);
        check(hasMove(moves, 1, 0), "馬可走一格右");
        check(hasMove(moves, 0, 1), "馬可走一格下");
        check(!hasMove(moves, 1, 1), "不能斜走");
        check(!hasMove(moves, 2, 0), "不能一次走两格");
        check(g.play(0, 0, 1, 0), "执行一步走子");
        checkEq(g.pieceCodeAt(1, 0), BanqiGame.piece(BanqiGame.RED, BanqiGame.HORSE), "落点棋子正确");
        check(g.isEmptyAt(0, 0), "起点清空");
        checkEq(g.turnSeat(), BanqiGame.SEAT_SECOND, "走子后换手");
        checkEq(g.idlePlies(), 1, "非吃子走子累加无进展计数");

        // 暗子不能走
        Pos q = new Pos().hidden(0, 0, BanqiGame.RED, BanqiGame.ROOK);
        g.setup(q.board, q.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        checkEq(g.legalMovesFrom(0, 0).size(), 0, "暗子不能移动");
        checkEq(g.legalFlips().size(), 1, "暗子可翻");

        // 斜走/越格非法
        check(!g.play(0, 0, 1, 1), "暗子斜走非法");
    }

    private static void testCannonRules() {
        BanqiGame g = new BanqiGame(1L);

        // 炮隔一子跳吃
        Pos p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .put(2, 0, BanqiGame.BLACK, BanqiGame.PAWN, true)
                .put(4, 0, BanqiGame.BLACK, BanqiGame.ROOK, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(hasMove(g.legalMovesFrom(0, 0), 4, 0), "炮隔一子跳吃");
        check(hasMove(g.legalMovesFrom(0, 0), 1, 0), "炮可走一格空格");
        check(!hasMove(g.legalMovesFrom(0, 0), 2, 0), "炮不能走步吃炮架");

        // 暗子可作炮架
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .hidden(2, 0, BanqiGame.BLACK, BanqiGame.PAWN)
                .put(4, 0, BanqiGame.BLACK, BanqiGame.ROOK, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(hasMove(g.legalMovesFrom(0, 0), 4, 0), "暗子可作炮架");

        // 暗子不能作为被吃目标
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .put(2, 0, BanqiGame.BLACK, BanqiGame.PAWN, true)
                .hidden(4, 0, BanqiGame.BLACK, BanqiGame.ROOK);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(!hasMove(g.legalMovesFrom(0, 0), 4, 0), "炮不能吃暗子");

        // 两个炮架不可连跳；只能吃第一个炮架后的第一枚明子
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .put(2, 0, BanqiGame.BLACK, BanqiGame.PAWN, true)
                .put(3, 0, BanqiGame.BLACK, BanqiGame.ADVISOR, true)
                .put(4, 0, BanqiGame.BLACK, BanqiGame.ROOK, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(hasMove(g.legalMovesFrom(0, 0), 3, 0), "炮吃炮架后第一枚明子");
        check(!hasMove(g.legalMovesFrom(0, 0), 4, 0), "炮不可连跳");

        // 无炮架不能跳吃
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .put(3, 0, BanqiGame.BLACK, BanqiGame.ROOK, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(!hasMove(g.legalMovesFrom(0, 0), 3, 0), "无炮架不能跳吃");

        // 己方子不能作为目标
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.PAWN, true)
                .put(2, 0, BanqiGame.RED, BanqiGame.ROOK, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(!hasMove(g.legalMovesFrom(0, 0), 2, 0), "炮不能吃己方子");

        // 远距离隔一子
        p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.CANNON, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.PAWN, true)
                .put(3, 0, BanqiGame.BLACK, BanqiGame.KING, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(hasMove(g.legalMovesFrom(0, 0), 3, 0), "炮隔子可吃將");
    }

    private static void testWinByCapture() {
        BanqiGame g = new BanqiGame(1L);
        Pos p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.ROOK, true)
                .put(1, 0, BanqiGame.BLACK, BanqiGame.HORSE, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        check(g.play(0, 0, 1, 0), "車吃馬");
        check(!g.hasAnyAction(BanqiGame.SEAT_SECOND), "黑方无子无步");
        checkEq(g.result(), BanqiGame.RED, "红方获胜");
        checkEq(g.winnerSeat(), BanqiGame.SEAT_FIRST, "胜者为座位 1");
        check(g.endReason().contains("无子可动"), "终局文案含无子可动: " + g.endReason());
    }

    private static void testStalemateLoses() {
        BanqiGame g = new BanqiGame(1L);
        Pos p = new Pos().put(0, 0, BanqiGame.BLACK, BanqiGame.ADVISOR, true)
                .put(1, 0, BanqiGame.RED, BanqiGame.KING, true)
                .put(0, 1, BanqiGame.RED, BanqiGame.KING, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_SECOND, 0);
        g.refreshEnd();
        checkEq(g.result(), BanqiGame.RED, "黑方被困判负");
        check(g.endReason().contains("无子可动"), "困毙文案: " + g.endReason());
    }

    private static void testIdleDraw() {
        BanqiGame g = new BanqiGame(1L);
        Pos p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.ADVISOR, true)
                .put(7, 3, BanqiGame.BLACK, BanqiGame.ADVISOR, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        Random rnd = new Random(2026L);
        int guard = 0;
        while (!g.isOver() && guard++ < 60) {
            List<BanqiGame.Move> candidates = new ArrayList<BanqiGame.Move>();
            for (BanqiGame.Move m : g.legalActions()) {
                if (m.flip) {
                    continue;
                }
                BanqiGame sim = g.copyForSearch();
                if (!sim.play(m)) {
                    continue;
                }
                if (sim.repeatCountAtCurrent() >= 2) {
                    continue;
                }
                if (sim.isOver() && !sim.endReason().contains("无吃子")) {
                    continue;
                }
                candidates.add(m);
            }
            if (candidates.isEmpty()) {
                break;
            }
            BanqiGame.Move pick = candidates.get(rnd.nextInt(candidates.size()));
            check(g.play(pick), "无进展测试走子合法 " + pick.key());
        }
        check(g.isOver(), "无进展测试应终局");
        checkEq(g.result(), BanqiGame.RESULT_DRAW, "30 步无进展判和");
        check(g.endReason().contains("30"), "和棋文案: " + g.endReason());
        checkEq(g.plies(), BanqiGame.DRAW_IDLE_PLIES, "恰好 30 步判和");
    }

    private static void testRepetitionDraw() {
        BanqiGame g = new BanqiGame(1L);
        Pos p = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.ADVISOR, true)
                .put(7, 3, BanqiGame.BLACK, BanqiGame.ADVISOR, true);
        g.setup(p.board, p.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        int[][] seq = {
                {0, 0, 0, 1}, {7, 3, 7, 2},
                {0, 1, 0, 0}, {7, 2, 7, 3},
                {0, 0, 0, 1}, {7, 3, 7, 2},
                {0, 1, 0, 0}, {7, 2, 7, 3}
        };
        for (int i = 0; i < seq.length; i++) {
            check(g.play(seq[i][0], seq[i][1], seq[i][2], seq[i][3]),
                    "循环测试第 " + (i + 1) + " 步合法");
        }
        checkEq(g.result(), BanqiGame.RESULT_DRAW, "同一局面三次判和");
        check(g.endReason().contains("三次"), "循环和棋文案: " + g.endReason());
        checkEq(g.plies(), 8, "第 8 步触发循环和棋");
    }

    private static void testFenRoundTrip() {
        BanqiGame g = new BanqiGame(99L);
        for (int i = 0; i < 4; i++) {
            check(g.flip(g.legalFlips().get(i).x, g.legalFlips().get(i).y), "FEN 测试翻子");
        }
        if (!g.legalMovesFrom(0, 0).isEmpty()) {
            BanqiGame.Move m = g.legalMovesFrom(0, 0).get(0);
            g.play(m.x, m.y, m.tx, m.ty);
        }
        String fen = g.fen();
        BanqiGame h = new BanqiGame(1L);
        check(h.loadFen(fen), "FEN 可解析: " + fen);
        checkEq(h.fen(), fen, "FEN 往返一致");
        checkEq(h.positionKey(), g.positionKey(), "局面键一致");
        checkEq(h.turnSeat(), g.turnSeat(), "轮次一致");
        checkEq(h.colorOfSeat(1), g.colorOfSeat(1), "座位 1 颜色一致");
        checkEq(h.colorOfSeat(2), g.colorOfSeat(2), "座位 2 颜色一致");
        checkEq(h.idlePlies(), g.idlePlies(), "无进展计数一致");
        checkEq(h.plies(), g.plies(), "步数一致");
        checkEq(h.result(), g.result(), "结果一致");
        check(!h.isFullSetKnown(), "镜像模式不持有暗子身份");
        check(h.unseenPool() == null, "镜像模式暗子池不可读");
        checkEq(h.legalFlips().size(), g.legalFlips().size(), "暗子数量一致");
    }

    private static void testAiSelfPlay() {
        for (int seed = 1; seed <= 3; seed++) {
            BanqiGame g = new BanqiGame(seed * 13L);
            Random rnd = new Random(seed * 1000L + 7);
            int steps = 0;
            while (!g.isOver() && steps < 400) {
                BanqiGame.Move m = BanqiAI.choose(g, 5, rnd);
                check(m != null, "AI 有合法着 seed=" + seed + " ply=" + g.plies());
                if (m == null) {
                    break;
                }
                boolean ok = g.play(m);
                check(ok, "AI 着法合法 seed=" + seed + " move=" + m.key() + " ply=" + g.plies());
                if (!ok) {
                    break;
                }
                steps++;
            }
            check(g.isOver(), "AI 对局可终局 seed=" + seed + " plies=" + g.plies());
            System.out.println("[AI self-play] seed=" + seed + " plies=" + g.plies()
                    + " result=" + g.result() + " reason=" + g.endReason());
        }

        // 高段位单步决策可运行
        BanqiGame g = new BanqiGame(2026L);
        for (int i = 0; i < 6; i++) {
            g.flip(g.legalFlips().get(0).x, g.legalFlips().get(0).y);
        }
        BanqiGame.Move high = BanqiAI.choose(g, 9, new Random(5L));
        check(high != null, "9 段 AI 可决策");
        check(g.play(high), "9 段 AI 着法合法");
    }

    private static void testAiDoesNotUseHiddenIdentity() {
        // 同一明面、同一暗子池，只交换两枚暗子的位置身份；AI 选择应完全一致
        Pos a = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.KING, true)
                .put(7, 3, BanqiGame.BLACK, BanqiGame.KING, true)
                .hidden(3, 1, BanqiGame.RED, BanqiGame.ROOK)
                .hidden(4, 1, BanqiGame.BLACK, BanqiGame.ROOK);
        Pos b = new Pos().put(0, 0, BanqiGame.RED, BanqiGame.KING, true)
                .put(7, 3, BanqiGame.BLACK, BanqiGame.KING, true)
                .hidden(3, 1, BanqiGame.BLACK, BanqiGame.ROOK)
                .hidden(4, 1, BanqiGame.RED, BanqiGame.ROOK);

        BanqiGame ga = new BanqiGame(1L);
        ga.setup(a.board, a.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);
        BanqiGame gb = new BanqiGame(1L);
        gb.setup(b.board, b.up, BanqiGame.RED, BanqiGame.SEAT_FIRST, 0);

        checkEq(ga.fen(), gb.fen(), "两局公开信息一致");
        BanqiGame.Move ma = BanqiAI.choose(ga, 8, new Random(123L));
        BanqiGame.Move mb = BanqiAI.choose(gb, 8, new Random(123L));
        check(ma != null && mb != null, "AI 在对称局面可决策");
        if (ma != null && mb != null) {
            checkEq(ma.key(), mb.key(), "AI 不依赖暗子具体身份");
        }
    }

    private static void testRandomPlayouts() {
        for (int seed = 1; seed <= 20; seed++) {
            BanqiGame g = new BanqiGame(seed * 31L);
            Random rnd = new Random(seed * 77L);
            int steps = 0;
            while (!g.isOver() && steps < 500) {
                List<BanqiGame.Move> actions = g.legalActions();
                check(!actions.isEmpty(), "随机对局始终有合法着 seed=" + seed);
                if (actions.isEmpty()) {
                    break;
                }
                BanqiGame.Move m = actions.get(rnd.nextInt(actions.size()));
                check(g.play(m), "随机着法合法 seed=" + seed + " move=" + m.key());
                steps++;
            }
            check(g.isOver(), "随机对局可终局 seed=" + seed + " plies=" + g.plies());
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static final class Pos {
        final int[][] board = new int[BanqiGame.ROWS][BanqiGame.COLS];
        final boolean[][] up = new boolean[BanqiGame.ROWS][BanqiGame.COLS];

        Pos put(int x, int y, int color, int type, boolean faceUp) {
            board[y][x] = BanqiGame.piece(color, type);
            up[y][x] = faceUp;
            return this;
        }

        Pos hidden(int x, int y, int color, int type) {
            return put(x, y, color, type, false);
        }
    }

    private static boolean hasMove(List<BanqiGame.Move> moves, int tx, int ty) {
        for (BanqiGame.Move m : moves) {
            if (!m.flip && m.tx == tx && m.ty == ty) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + message);
        }
    }

    private static void checkEq(int actual, int expected, String message) {
        checks++;
        if (actual != expected) {
            failures++;
            System.out.println("FAIL: " + message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void checkEq(String actual, String expected, String message) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            failures++;
            System.out.println("FAIL: " + message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
