package com.shengli.watermark;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 打卡水印：左下角方块时间、日期星期、黄线、地点、经纬度、自定义文字、校验状态；
 * 右下角品牌和防伪码。
 */
public final class Watermark {
    private static final int BOX = Color.argb(215, 84, 99, 128);
    private static final int YELLOW = Color.rgb(255, 193, 7);
    private static final String[] WEEK = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};

    private Watermark() { }

    public static String weekday(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return WEEK[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    public static String coords(double lat, double lng) {
        return String.format(Locale.US, "%.6f°%s  %.6f°%s",
                Math.abs(lng), lng >= 0 ? "E" : "W", Math.abs(lat), lat >= 0 ? "N" : "S");
    }

    public static void draw(Canvas c, int w, int h, WatermarkData d) {
        float u = Math.min(w, h) / 1080f;
        float x0 = 24 * u;
        float colW = Math.min(w * 0.62f, 520 * u);

        Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        TextPaint digitP = tp(64 * u, Color.WHITE, bold);
        TextPaint colonP = tp(60 * u, Color.WHITE, bold);
        TextPaint dateP = tp(56 * u, Color.WHITE, Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        TextPaint addrP = tp(42 * u, Color.WHITE, Typeface.DEFAULT);
        TextPaint smallP = tp(36 * u, Color.WHITE, Typeface.DEFAULT);
        TextPaint statusP = tp(36 * u, Color.argb(235, 255, 255, 255), Typeface.DEFAULT);

        float iconW = 54 * u;
        float textW = colW - iconW;

        List<Object[]> blocks = new ArrayList<>(); // {layout, iconType}
        if (d.showAddress && d.address != null && !d.address.isEmpty()) {
            blocks.add(new Object[]{layout(d.address, addrP, textW, 3, u), "pin"});
        }
        if (d.showCoords && !Double.isNaN(d.lat)) {
            blocks.add(new Object[]{layout("经纬度 " + coords(d.lat, d.lng), smallP, textW, 2, u), ""});
        }
        for (String s : d.lines) {
            if (s != null && !s.trim().isEmpty()) blocks.add(new Object[]{layout(s.trim(), addrP, textW, 2, u), ""});
        }
        String status = d.verified ? "水印相机·时间地点未修改" : d.statusNote;
        StaticLayout statusL = (status == null || status.isEmpty()) ? null : layout(status, statusP, textW, 1, u);

        float boxH = 100 * u, boxW = 84 * u, colonW = 38 * u;
        Paint.FontMetrics dm = dateP.getFontMetrics();
        float dateH = dm.descent - dm.ascent;
        float total = boxH + 14 * u + dateH + 10 * u + 6 * u + 14 * u;
        for (Object[] b : blocks) total += ((StaticLayout) b[0]).getHeight() + 8 * u;
        if (statusL != null) total += statusL.getHeight() + 6 * u;

        float y = h - 22 * u - total;

        // 方块时间
        String hms = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(d.timeMillis);
        String[] parts = hms.split(":");
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(BOX);
        float x = x0;
        Paint.FontMetrics fm = digitP.getFontMetrics();
        for (int i = 0; i < 3; i++) {
            c.drawRoundRect(new RectF(x, y, x + boxW, y + boxH), 10 * u, 10 * u, boxPaint);
            float tw = digitP.measureText(parts[i]);
            c.drawText(parts[i], x + (boxW - tw) / 2f, y + boxH / 2f - (fm.ascent + fm.descent) / 2f, digitP);
            x += boxW;
            if (i < 2) {
                float cw = colonP.measureText(":");
                c.drawText(":", x + (colonW - cw) / 2f, y + boxH / 2f - (fm.ascent + fm.descent) / 2f - 4 * u, colonP);
                x += colonW;
            }
        }
        y += boxH + 14 * u;

        // 日期 星期
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(d.timeMillis) + "  " + weekday(d.timeMillis);
        c.drawText(date, x0, y - dm.ascent, dateP);
        y += dateH + 10 * u;

        // 黄线
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(YELLOW);
        c.drawRect(x0, y, x0 + colW, y + 6 * u, line);
        y += 6 * u + 14 * u;

        // 地点、经纬度、文字
        for (Object[] b : blocks) {
            StaticLayout sl = (StaticLayout) b[0];
            if ("pin".equals(b[1])) drawPin(c, x0 + 10 * u, y + 6 * u, 30 * u);
            c.save();
            c.translate(x0 + iconW, y);
            sl.draw(c);
            c.restore();
            y += sl.getHeight() + 8 * u;
        }
        if (statusL != null) {
            drawShield(c, x0 + 6 * u, y + 4 * u, 34 * u, d.verified);
            c.save();
            c.translate(x0 + iconW, y);
            statusL.draw(c);
            c.restore();
        }

        drawBrand(c, w, h, u, d);
    }

    private static void drawBrand(Canvas c, int w, int h, float u, WatermarkData d) {
        float right = w - 20 * u;
        float y = h - 14 * u;
        Typeface b = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        if (d.verified && d.code != null && !d.code.isEmpty()) {
            TextPaint codeP = tp(30 * u, Color.WHITE, Typeface.create("sans-serif-condensed", Typeface.BOLD));
            String s = "防伪 " + d.code;
            c.drawText(s, right - codeP.measureText(s), y, codeP);
            y -= 30 * u + 14 * u;
        }
        // 白色小标签
        TextPaint badgeP = tp(28 * u, Color.rgb(40, 40, 40), b);
        badgeP.clearShadowLayer();
        String badge = "真实时间";
        float bw = badgeP.measureText(badge) + 24 * u, bh = 44 * u;
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(Color.WHITE);
        RectF r = new RectF(right - bw, y - bh, right, y);
        c.drawRoundRect(r, 6 * u, 6 * u, white);
        Paint.FontMetrics fm = badgeP.getFontMetrics();
        c.drawText(badge, r.left + 12 * u, r.centerY() - (fm.ascent + fm.descent) / 2f, badgeP);
        y -= bh + 10 * u;

        TextPaint l2 = tp(44 * u, Color.WHITE, b);
        String s2 = "水印相机";
        c.drawText(s2, right - l2.measureText(s2), y, l2);
        y -= 44 * u + 8 * u;
        TextPaint l1 = tp(40 * u, Color.WHITE, b);
        String s1 = "· 真实 ·";
        float w2 = l2.measureText(s2);
        c.drawText(s1, right - w2 / 2f - l1.measureText(s1) / 2f, y, l1);
    }

    private static void drawPin(Canvas c, float x, float y, float s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setShadowLayer(s / 10f, 0, s / 20f, Color.argb(140, 0, 0, 0));
        float r = s / 2f;
        Path path = new Path();
        path.addCircle(x + r, y + r, r, Path.Direction.CW);
        Path tip = new Path();
        tip.moveTo(x + r * 0.15f, y + r * 1.45f);
        tip.lineTo(x + r, y + s * 1.35f);
        tip.lineTo(x + r * 1.85f, y + r * 1.45f);
        tip.close();
        path.op(tip, Path.Op.UNION);
        Path hole = new Path();
        hole.addCircle(x + r, y + r, r * 0.42f, Path.Direction.CW);
        path.op(hole, Path.Op.DIFFERENCE);
        c.drawPath(path, p);
    }

    private static void drawShield(Canvas c, float x, float y, float s, boolean ok) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s / 11f);
        p.setColor(ok ? Color.WHITE : Color.rgb(255, 193, 7));
        p.setStrokeJoin(Paint.Join.ROUND);
        Path sh = new Path();
        sh.moveTo(x + s / 2f, y);
        sh.lineTo(x + s, y + s * 0.18f);
        sh.lineTo(x + s, y + s * 0.5f);
        sh.quadTo(x + s, y + s * 0.88f, x + s / 2f, y + s * 1.05f);
        sh.quadTo(x, y + s * 0.88f, x, y + s * 0.5f);
        sh.lineTo(x, y + s * 0.18f);
        sh.close();
        c.drawPath(sh, p);
        Path mark = new Path();
        if (ok) {
            mark.moveTo(x + s * 0.28f, y + s * 0.52f);
            mark.lineTo(x + s * 0.45f, y + s * 0.68f);
            mark.lineTo(x + s * 0.74f, y + s * 0.36f);
        } else {
            mark.moveTo(x + s / 2f, y + s * 0.28f);
            mark.lineTo(x + s / 2f, y + s * 0.6f);
            mark.moveTo(x + s / 2f, y + s * 0.74f);
            mark.lineTo(x + s / 2f, y + s * 0.78f);
        }
        c.drawPath(mark, p);
    }

    private static StaticLayout layout(String s, TextPaint p, float width, int maxLines, float u) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, (int) Math.max(10, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2 * u, 1f)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build();
    }

    private static TextPaint tp(float size, int color, Typeface tf) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(tf);
        p.setShadowLayer(Math.max(1f, size / 12f), 0, size / 28f, Color.argb(170, 0, 0, 0));
        return p;
    }
}
