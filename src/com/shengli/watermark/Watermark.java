package com.shengli.watermark;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

/** 胜利水印相机的水印绘制：左下角信息块 + 右下角品牌小字。 */
public final class Watermark {
    private static final int RED = Color.rgb(220, 38, 38);
    private static final int YELLOW = Color.rgb(250, 204, 21);
    private static final String[] WEEK = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};

    private Watermark() { }

    public static String weekday(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return WEEK[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    public static List<String> infoLines(WatermarkData d) {
        List<String> lines = new ArrayList<>();
        if (d.showAddress && d.address != null && !d.address.isEmpty()) lines.add("地点：" + d.address);
        if (d.showCoords && !Double.isNaN(d.lat)) {
            lines.add(String.format(Locale.US, "经纬度：%.6f°%s, %.6f°%s",
                    Math.abs(d.lat), d.lat >= 0 ? "N" : "S", Math.abs(d.lng), d.lng >= 0 ? "E" : "W"));
        }
        if (d.person != null && !d.person.isEmpty()) lines.add("打卡人：" + d.person);
        if (d.note != null && !d.note.isEmpty()) lines.add("备注：" + d.note);
        return lines;
    }

    /** 在宽 w、高 h 的画布上绘制水印。 */
    public static void draw(Canvas canvas, int w, int h, WatermarkData d) {
        float u = Math.min(w, h) / 1080f;          // 以 1080 宽为基准缩放
        float margin = 40 * u;
        float pad = 28 * u;
        float boxW = Math.min(w - margin * 2, 820 * u);
        float textW = boxW - pad * 2;

        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(d.timeMillis);
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(d.timeMillis)
                + "  " + weekday(d.timeMillis);

        TextPaint timeP = paint(110 * u, Color.WHITE, true);
        TextPaint dateP = paint(40 * u, Color.WHITE, true);
        TextPaint titleP = paint(38 * u, Color.WHITE, true);
        TextPaint infoP = paint(34 * u, Color.WHITE, false);

        String title = (d.title == null || d.title.isEmpty()) ? "工作打卡" : d.title;
        float titleW = titleP.measureText(title) + 36 * u;
        float titleH = 64 * u;

        List<StaticLayout> infos = new ArrayList<>();
        float infoH = 0;
        for (String line : infoLines(d)) {
            StaticLayout sl = StaticLayout.Builder.obtain(line, 0, line.length(), infoP, (int) textW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(4 * u, 1f)
                    .setMaxLines(3)
                    .build();
            infos.add(sl);
            infoH += sl.getHeight() + 10 * u;
        }

        Paint.FontMetrics tm = timeP.getFontMetrics();
        float timeH = tm.descent - tm.ascent;
        float headerH = Math.max(timeH, titleH);
        float dividerGap = 22 * u;
        float boxH = pad + headerH + 8 * u + 48 * u + (infos.isEmpty() ? 0 : dividerGap + infoH) + pad - 10 * u;

        float left = margin;
        float bottom = h - margin - 36 * u;
        float top = bottom - boxH;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(120, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left, top, left + boxW, bottom), 22 * u, 22 * u, bg);

        // 左侧黄色竖条
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(YELLOW);
        canvas.drawRoundRect(new RectF(left, top, left + 10 * u, bottom), 5 * u, 5 * u, bar);

        // 大号时间
        float x = left + pad;
        float y = top + pad - tm.ascent;
        canvas.drawText(time, x, y, timeP);

        // 红色标题胶囊
        float chipX = x + timeP.measureText(time) + 26 * u;
        float chipTop = top + pad + (timeH - titleH) / 2f;
        Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
        chip.setColor(RED);
        if (chipX + titleW > left + boxW - pad) titleW = left + boxW - pad - chipX;
        canvas.drawRoundRect(new RectF(chipX, chipTop, chipX + titleW, chipTop + titleH), 12 * u, 12 * u, chip);
        Paint.FontMetrics fm = titleP.getFontMetrics();
        float ty = chipTop + titleH / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.save();
        canvas.clipRect(chipX, chipTop, chipX + titleW, chipTop + titleH);
        canvas.drawText(title, chipX + 18 * u, ty, titleP);
        canvas.restore();

        // 日期星期
        float dateY = top + pad + headerH + 8 * u - dateP.getFontMetrics().ascent;
        dateP.setColor(YELLOW);
        canvas.drawText(date, x, dateY, dateP);

        float cy = top + pad + headerH + 8 * u + 48 * u;
        if (!infos.isEmpty()) {
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(Color.argb(110, 255, 255, 255));
            line.setStrokeWidth(Math.max(1, 2 * u));
            cy += dividerGap / 2f;
            canvas.drawLine(x, cy, left + boxW - pad, cy, line);
            cy += dividerGap / 2f;
            for (StaticLayout sl : infos) {
                canvas.save();
                canvas.translate(x, cy);
                sl.draw(canvas);
                canvas.restore();
                cy += sl.getHeight() + 10 * u;
            }
        }

        // 右下角品牌
        TextPaint brand = paint(26 * u, Color.argb(200, 255, 255, 255), false);
        String b = "胜利水印相机";
        canvas.drawText(b, w - margin - brand.measureText(b), h - margin, brand);
    }

    private static TextPaint paint(float size, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(bold ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD) : Typeface.DEFAULT);
        p.setShadowLayer(Math.max(1f, size / 14f), 0, size / 24f, Color.argb(160, 0, 0, 0));
        return p;
    }
}
