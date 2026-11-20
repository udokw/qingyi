package com.sijia.go.banqi;

/**
 * 暗棋规则变体的集中开关。
 *
 * <p>文档 v1 文末的 6 条待确认规则全部收在这里，改口时只改本文件，
 * 规则逻辑、协议与页面都不用动。默认值 = 规则文档 v1 采用方案。</p>
 */
public final class BanqiOptions {

    /** 1. 同级能否互吃：true = 可以（將吃將等）。 */
    public static boolean equalRankCanCapture = true;

    /** 2. 炮架能否是暗子：true = 可以。 */
    public static boolean cannonScreenMayBeHidden = true;

    /** 3. 炮能否走步吃子：false = 不可以（所有吃子都必须隔子跳吃）。 */
    public static boolean cannonCanCaptureByStep = false;

    /** 4. 首翻前谁先手：SEAT_FIRST / SEAT_SECOND。 */
    public static int firstSeat = BanqiGame.SEAT_FIRST;

    /** 5. 无进展和棋阈值（双方合计连续无吃子无翻子的步数）。 */
    public static int drawIdlePlies = BanqiGame.DRAW_IDLE_PLIES;

    /** 6. 联机座位 2 是否 180° 翻转显示（仅视觉，规则与坐标不变）。 */
    public static boolean flipForSeat2 = true;

    private BanqiOptions() {
    }
}
