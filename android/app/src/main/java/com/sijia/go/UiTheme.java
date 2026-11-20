package com.sijia.go;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/**
 * B/C 双主题(用户选定, 见 design-demos/direction-approved.md):
 *   MONUMENT = 方向 B「粉彩纪念碑」 柔和粉彩 + 圆角面板 + 珊瑚 accent
 * HARA     = 方向 C「墨白留白」    和纸暖白 + 墨色 + 朱红 accent + 发丝线
 */
public class UiTheme {
    public static final int MONUMENT = 0;
    public static final int HARA = 1;

    public int id;
    public int windowBg, titleColor, subColor, footColor, accent;
    public int[] entryBg;
    public int entryRadiusDp, entryTitle, entryDesc, prefixColor, dividerColor;
    public boolean hairlineList;
    public String[] prefixes;
    public int btnBg, btnText, btnStroke, btnRadiusDp;
    public int primaryBg, primaryText;
    public int dangerText, dangerStroke;
    public int boardBg, boardLine, marker;
    public int dlgLine;
    public boolean serifTitle;

    public static UiTheme current(Context ctx) {
        int id = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("theme", HARA);
        return of(id);
    }

    public static UiTheme toggle(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE);
        int next = sp.getInt("theme", HARA) == HARA ? MONUMENT : HARA;
        sp.edit().putInt("theme", next).apply();
        return of(next);
    }

    public String name() { return id == HARA ? "墨白" : "粉彩"; }

    public static UiTheme of(int id) {
        return id == MONUMENT ? monument() : hara();
    }

    /** 方向 B: 纪念碑谷式粉彩 */
    private static UiTheme monument() {
        UiTheme t = new UiTheme();
        t.id = MONUMENT;
        t.windowBg = Color.parseColor("#F6F1EA");
        t.titleColor = Color.parseColor("#3E3A36");
        t.subColor = Color.parseColor("#9A9186");
        t.footColor = Color.parseColor("#B0A79B");
        t.accent = Color.parseColor("#E8846B");
        t.entryBg = new int[]{Color.parseColor("#F5DFD8"), Color.parseColor("#DEE7DC"), Color.parseColor("#F3E9CF")};
        t.entryRadiusDp = 20;
        t.entryTitle = Color.parseColor("#3E3A36");
        t.entryDesc = Color.parseColor("#8A8072");
        t.prefixColor = Color.parseColor("#B0A79B");
        t.dividerColor = Color.TRANSPARENT;
        t.hairlineList = false;
        t.prefixes = new String[]{"01", "02", "03"};
        t.btnBg = Color.parseColor("#EFE7DA");
        t.btnText = Color.parseColor("#3E3A36");
        t.btnStroke = Color.TRANSPARENT;
        t.btnRadiusDp = 22;
        t.primaryBg = Color.parseColor("#B7CDB4");
        t.primaryText = Color.parseColor("#2F3A2E");
        t.dangerText = Color.parseColor("#B06A55");
        t.dangerStroke = Color.parseColor("#D8C9B8");
        t.boardBg = Color.parseColor("#F3E7CE");
        t.boardLine = Color.parseColor("#A08B6D");
        t.marker = Color.parseColor("#E8846B");
        t.dlgLine = Color.parseColor("#E3D9C8");
        t.serifTitle = true;
        return t;
    }

    /** 方向 C: 原研哉式墨白 */
    private static UiTheme hara() {
        UiTheme t = new UiTheme();
        t.id = HARA;
        t.windowBg = Color.parseColor("#F7F4ED");
        t.titleColor = Color.parseColor("#1A1A1A");
        t.subColor = Color.parseColor("#6B665C");
        t.footColor = Color.parseColor("#9A9384");
        t.accent = Color.parseColor("#C73E2B");
        int w = t.windowBg;
        t.entryBg = new int[]{w, w, w};
        t.entryRadiusDp = 0;
        t.entryTitle = Color.parseColor("#1A1A1A");
        t.entryDesc = Color.parseColor("#8A8474");
        t.prefixColor = t.accent;
        t.dividerColor = Color.parseColor("#DDD6C4");
        t.hairlineList = true;
        t.prefixes = new String[]{"壹", "贰", "叁"};
        t.btnBg = Color.TRANSPARENT;
        t.btnText = Color.parseColor("#1A1A1A");
        t.btnStroke = Color.parseColor("#CFC8B8");
        t.btnRadiusDp = 2;
        t.primaryBg = Color.parseColor("#1A1A1A");
        t.primaryText = Color.parseColor("#F7F4ED");
        t.dangerText = t.accent;
        t.dangerStroke = t.btnStroke;
        t.boardBg = Color.parseColor("#E3B771");
        t.boardLine = Color.parseColor("#3A3226");
        t.marker = Color.parseColor("#C73E2B");
        t.dlgLine = t.dividerColor;
        t.serifTitle = true;
        return t;
    }

    public GradientDrawable entryDrawable(int index) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(entryBg[index]);
        g.setCornerRadius(dp(entryRadiusDp));
        return g;
    }

    /** kind: 0 普通, 1 主按钮, 2 危险(认输); 墨白=透明底(网格线画在容器上), 粉彩=pill(认输粉底) */
    public GradientDrawable buttonDrawable(int kind) {
        GradientDrawable g = new GradientDrawable();
        if (hairlineList) {
            g.setColor(Color.TRANSPARENT);
            return g;
        }
        if (kind == 1) g.setColor(primaryBg);
        else if (kind == 2) g.setColor(entryBg[0]);
        else g.setColor(btnBg);
        g.setCornerRadius(dp(btnRadiusDp));
        return g;
    }

    public int buttonTextColor(int kind) {
        if (hairlineList) return kind == 2 ? dangerText : btnText;
        if (kind == 1) return primaryText;
        if (kind == 2) return dangerText;
        return btnText;
    }

    /** 弹窗背景: 主题底色 + 圆角(墨白 6dp / 粉彩 20dp) */
    public GradientDrawable dialogBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(windowBg);
        g.setCornerRadius(dp(id == HARA ? 6 : 20));
        return g;
    }

    /** 单选圆点: 描边圈, 选中时内嵌 accent 实心点 */
    public Drawable radioDrawable(boolean checked) {
        float d = Resources.getSystem().getDisplayMetrics().density;
        int sz = (int) (20 * d);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Math.max(1, (int) (1.5f * d)), subColor);
        ring.setColor(Color.TRANSPARENT);
        ring.setSize(sz, sz);
        if (!checked) return ring;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ring, dot});
        int in = (int) (5.5f * d);
        ld.setLayerInset(1, in, in, in, in);
        return ld;
    }

    /** 滑杆轨道: 细线 + accent 进度 */
    public Drawable seekTrack() {
        float d = Resources.getSystem().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dlgLine);
        bg.setCornerRadius(2 * d);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(accent);
        fg.setCornerRadius(2 * d);
        ClipDrawable clip = new ClipDrawable(fg, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        int v = (int) (6 * d);   // thumb 16dp, 轨道垂直居中成 3~4dp 细线
        ld.setLayerInset(0, 0, v, 0, v);
        ld.setLayerInset(1, 0, v, 0, v);
        return ld;
    }

    /** 滑杆圆点: 墨黑(墨白) / 珊瑚(粉彩) */
    public Drawable seekThumb() {
        float d = Resources.getSystem().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(id == HARA ? titleColor : accent);
        g.setSize((int) (16 * d), (int) (16 * d));
        return g;
    }

    /** 主题化 AlertDialog: serif 标题(customTitle 在 builder 阶段设置) */
    public static AlertDialog.Builder themedBuilder(Context c, UiTheme t, String title) {
        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextSize(17);
        tv.setTextColor(t.titleColor);
        tv.setTypeface(Typeface.create("serif", Typeface.BOLD));
        tv.setLetterSpacing(0.08f);
        int p = t.dp(22);
        tv.setPadding(p, t.dp(20), p, t.dp(4));
        return new AlertDialog.Builder(c).setCustomTitle(tv);
    }

    /** show() 之后调用: 窗口圆角底色 + 正文/按钮配色 */
    public static void styleDialog(AlertDialog d, UiTheme t, boolean dangerOk) {
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(t.dialogBackground());
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextColor(t.subColor);
            msg.setTextSize(14);
            msg.setLineSpacing(0, 1.35f);
        }
        Button p = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (p != null) {
            p.setTextColor(dangerOk ? t.dangerText : t.titleColor);
            p.setTypeface(Typeface.DEFAULT_BOLD);
        }
        Button n = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (n != null) n.setTextColor(t.subColor);
    }

    private int dp(int v) {
        return (int) (v * android.content.res.Resources.getSystem().getDisplayMetrics().density + 0.5f);
    }
}
