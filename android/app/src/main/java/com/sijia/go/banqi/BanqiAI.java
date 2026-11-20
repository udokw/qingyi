package com.sijia.go.banqi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 暗棋启发式 AI。
 *
 * <p>暗棋是不完全信息博弈，本 AI 不做完全信息搜索：</p>
 * <ul>
 *   <li>走子/吃子：模拟一步后与当前局面比较，取“局面增量”；</li>
 *   <li>翻子：对剩余暗子池逐个身份做模拟，取期望局面增量，不读取任何具体格子的暗子身份；</li>
 *   <li>评估包含子力、机动性、互相威胁、以及“子力是否已激活”；</li>
 *   <li>接近无进展和棋阈值时提高翻子意愿，避免被动判和。</li>
 * </ul>
 *
 * <p>评分口径（修复要点）：翻子与走子必须在同一参照系里比较，统一为
 * “执行动作后的局面值 − 当前局面值”。此前翻子额外加固定推进分，而评估里带
 * 先后手分（走完一手必然由 +0.35 变 −0.35），导致任何不吃的走子天生比任何翻子
 * 低约 0.55 分，高段位也只会翻子。现在两端都是增量分，吃子收益、被吃风险、
 * 明暗状态才能在同一个尺度上竞争。</p>
 *
 * <p>段位 1~10：1 纯随机；2~3 大噪声；4~5 中噪声；6~8 小噪声 + 反击检查；
 * 9~10 取评估最优。</p>
 */
public final class BanqiAI {

    private static final double[] NOISE = {0, 0, 4.5, 3.0, 1.8, 1.0, 0.55, 0.28, 0.12, 0.04, 0};
    private static final double[] TOP_RATIO = {1, 1, 0.70, 0.55, 0.45, 0.35, 0.25, 0.15, 0.07, 0.03, 0.01};

    private BanqiAI() {
    }

    public static BanqiGame.Move choose(BanqiGame game, int level, Random random) {
        if (game == null || game.isOver()) {
            return null;
        }
        int lv = Math.max(1, Math.min(10, level));
        Random rnd = random == null ? new Random() : random;
        List<BanqiGame.Move> actions = game.legalActions();
        if (actions.isEmpty()) {
            return null;
        }
        if (lv == 1) {
            return actions.get(rnd.nextInt(actions.size()));
        }

        double base = evaluate(game, game.turnSeat());
        int seat = game.turnSeat();
        List<Scored> scored = new ArrayList<Scored>(actions.size());
        for (BanqiGame.Move m : actions) {
            double score;
            if (m.flip) {
                score = flipScore(game, m, seat, base);
            } else {
                score = moveScore(game, m, seat, lv, base);
            }
            score += rnd.nextDouble() * NOISE[lv];
            scored.add(new Scored(m, score));
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                return Double.compare(b.score, a.score);
            }
        });
        int top = Math.max(1, (int) Math.ceil(scored.size() * TOP_RATIO[lv]));
        return scored.get(rnd.nextInt(top)).move;
    }

    // ------------------------------------------------------------------
    // 翻子期望值
    // ------------------------------------------------------------------

    /**
     * 翻子得分：对暗子池里每一种可能的身份做一次模拟，取评估增量的期望。
     * 翻出己方强子会加分，翻出对方强子会减分，翻出的明子若立刻被吃再扣一次风险。
     */
    private static double flipScore(BanqiGame game, BanqiGame.Move m, int seat, double base) {
        int[] pool = game.unseenPool();
        double score = 0;
        if (pool != null) {
            int myColor = game.colorOfSeat(seat);
            double weighted = 0;
            int total = 0;
            for (int p = 1; p < pool.length; p++) {
                int n = pool[p];
                if (n <= 0) {
                    continue;
                }
                BanqiGame sim = game.copyForSearch();
                if (!sim.flipWith(m.x, m.y, p)) {
                    continue;
                }
                double delta = evaluate(sim, seat) - base;
                // 翻出己方子却紧贴可吃它的敌子：按大概率丢子折价，避免只会无脑翻子送子。
                if (myColor != 0 && BanqiGame.colorOf(p) == myColor
                        && canBeCaptured(sim, m.x, m.y, 3 - myColor)) {
                    delta -= 0.6 * pieceValue(BanqiGame.typeOf(p));
                }
                weighted += delta * n;
                total += n;
            }
            if (total > 0) {
                score = weighted / total;
            }
        }
        // 开局的翻子价值来自“激活暗子”：暗子越多越值得翻，进入残局后迅速衰减，
        // 避免已有子力优势时还一味翻子。
        double hidden = hiddenRatio(game);
        score += 0.30 * hidden;
        // 接近无进展和棋阈值时，翻子能重置计数，适当提高意愿。
        score += Math.max(0, game.idlePlies() - 12) * 0.15;
        score -= flipRisk(game, m.x, m.y, seat);
        return score;
    }

    /** 该格明子在下一次对方行动时是否会被直接吃掉。 */
    private static boolean canBeCaptured(BanqiGame sim, int x, int y, int byColor) {
        for (BanqiGame.Move mv : sim.movesForColor(byColor)) {
            if (mv.tx == x && mv.ty == y && mv.captured != BanqiGame.EMPTY) {
                return true;
            }
        }
        return false;
    }

    /** 翻出的明子若紧贴敌方明子，容易被立即吃掉，做位置风险折扣。 */
    private static double flipRisk(BanqiGame game, int x, int y, int seat) {
        int myColor = game.colorOfSeat(seat);
        if (myColor == 0) {
            return 0;
        }
        int enemy = 3 - myColor;
        double risk = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0];
            int ny = y + d[1];
            if (!game.isFaceUp(nx, ny)) {
                continue;
            }
            int p = game.pieceCodeAt(nx, ny);
            if (BanqiGame.colorOf(p) == enemy) {
                risk += 0.10 + pieceValue(BanqiGame.typeOf(p)) * 0.015;
            }
        }
        return risk;
    }

    // ------------------------------------------------------------------
    // 走子评分
    // ------------------------------------------------------------------

    private static double moveScore(BanqiGame game, BanqiGame.Move m, int seat, int level,
                                    double base) {
        BanqiGame sim = game.copyForSearch();
        if (!sim.play(m)) {
            return -10000;
        }
        double score = evaluate(sim, seat) - base;
        if (level >= 6 && !sim.isOver()) {
            score += opponentBestCaptureDelta(sim, seat) * 0.85;
        }
        return score;
    }

    /** 对手最佳吃子带来的分差（≤0），用于避免白送子。 */
    private static double opponentBestCaptureDelta(BanqiGame game, int seat) {
        double base = evaluate(game, seat);
        double worst = 0;
        for (BanqiGame.Move m : game.legalActions()) {
            if (m.flip || m.captured == BanqiGame.EMPTY) {
                continue;
            }
            BanqiGame sim = game.copyForSearch();
            if (sim.play(m)) {
                worst = Math.min(worst, evaluate(sim, seat) - base);
            }
        }
        return worst;
    }

    // ------------------------------------------------------------------
    // 局面评估
    // ------------------------------------------------------------------

    private static double evaluate(BanqiGame game, int seat) {
        if (game.isOver()) {
            if (game.result() == BanqiGame.RESULT_DRAW) {
                return 0;
            }
            int overColor = game.colorOfSeat(seat);
            if (overColor == 0) {
                return 0;
            }
            return game.result() == overColor ? 1000 : -1000;
        }
        int myColor = game.colorOfSeat(seat);
        if (myColor == 0) {
            return 0;
        }
        int enemyColor = 3 - myColor;
        double score = 0;
        for (int y = 0; y < BanqiGame.ROWS; y++) {
            for (int x = 0; x < BanqiGame.COLS; x++) {
                if (!game.isFaceUp(x, y)) {
                    continue;
                }
                int p = game.pieceCodeAt(x, y);
                double v = pieceValue(BanqiGame.typeOf(p)) + 0.08 * centerValue(x, y);
                score += BanqiGame.colorOf(p) == myColor ? v : -v;
            }
        }
        List<BanqiGame.Move> mine = game.movesForColor(myColor);
        List<BanqiGame.Move> theirs = game.movesForColor(enemyColor);
        // 机动性：明子越多、可走法越多，说明子力已经激活；暗子再多也不算战力。
        score += 0.12 * (mine.size() - theirs.size());
        // 吃子威胁：能吃到对方明子是真实收益，反之是己方子力的安全隐患。
        score += 0.45 * (threatValue(mine) - threatValue(theirs));
        return score;
    }

    /** 一组着法里所有吃子目标的加权价值。 */
    private static double threatValue(List<BanqiGame.Move> moves) {
        double score = 0;
        for (BanqiGame.Move m : moves) {
            if (m.captured == BanqiGame.EMPTY) {
                continue;
            }
            int targetType = BanqiGame.typeOf(m.captured);
            double gain = pieceValue(targetType);
            if (targetType == BanqiGame.KING && BanqiGame.typeOf(m.piece) == BanqiGame.PAWN) {
                gain += 2.0;
            }
            score += 0.45 * gain;
        }
        return score;
    }

    /** 暗子占棋盘的比例，用于衡量“还需要翻子开发”的程度。 */
    private static double hiddenRatio(BanqiGame game) {
        int hidden = 0;
        for (int y = 0; y < BanqiGame.ROWS; y++) {
            for (int x = 0; x < BanqiGame.COLS; x++) {
                if (game.isHiddenAt(x, y)) {
                    hidden++;
                }
            }
        }
        return hidden / (double) (BanqiGame.ROWS * BanqiGame.COLS);
    }

    private static double centerValue(int x, int y) {
        double cx = 1.0 - Math.abs(x - 3.5) / 3.5;
        double cy = 1.0 - Math.abs(y - 1.5) / 1.5;
        return 0.10 * cx + 0.05 * cy;
    }

    private static double pieceValue(int type) {
        switch (type) {
            case BanqiGame.KING:
                return 8.5;
            case BanqiGame.ADVISOR:
                return 6.2;
            case BanqiGame.ELEPHANT:
                return 5.8;
            case BanqiGame.ROOK:
                return 5.0;
            case BanqiGame.HORSE:
                return 4.2;
            case BanqiGame.CANNON:
                return 4.6;
            case BanqiGame.PAWN:
                return 3.4;
            default:
                return 0;
        }
    }

    private static final class Scored {
        final BanqiGame.Move move;
        final double score;

        Scored(BanqiGame.Move move, double score) {
            this.move = move;
            this.score = score;
        }
    }
}
