package com.shengli.watermark;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 拍完照后的编辑页：在照片上加文字，手指拖动摆放位置，点文字可修改、换颜色、调大小或删除，
 * 「保存」时按真实像素把文字画进原图。
 */
public class PhotoEditor {
    public interface OnSave { void save(Bitmap bmp); }

    private static final String TAG = "photo_editor";
    private static final int[] COLORS = {Color.WHITE, Color.rgb(255, 193, 7), Color.rgb(239, 68, 68), Color.BLACK, Color.rgb(47, 91, 255)};
    private static final String[] COLOR_NAMES = {"白色", "黄色", "红色", "黑色", "蓝色"};

    private final Activity act;
    private final FrameLayout root;
    private final Bitmap photo;
    private final OnSave onSave;
    private final Runnable onCancel;

    private FrameLayout panel;
    private FrameLayout stage;
    private final List<TextView> stickers = new ArrayList<>();

    public PhotoEditor(Activity act, FrameLayout root, Bitmap photo, OnSave onSave, Runnable onCancel) {
        this.act = act;
        this.root = root;
        this.photo = photo;
        this.onSave = onSave;
        this.onCancel = onCancel;
    }

    /** 返回键：编辑页打开时先问是否放弃。 */
    public static boolean handleBack(FrameLayout root) {
        View v = root.findViewWithTag(TAG);
        if (v == null) return false;
        Object e = v.getTag(R_KEY);
        if (e instanceof PhotoEditor) ((PhotoEditor) e).confirmDiscard();
        return true;
    }

    private static final int R_KEY = 0x7f0f0001;

    public void show() {
        panel = new FrameLayout(act);
        panel.setTag(TAG);
        panel.setTag(R_KEY, this);
        panel.setBackgroundColor(Color.BLACK);
        panel.setClickable(true);

        int sw = act.getResources().getDisplayMetrics().widthPixels;
        int sh = act.getResources().getDisplayMetrics().heightPixels;
        int maxH = sh - dp(150);
        int w = sw, h = (int) ((long) sw * photo.getHeight() / photo.getWidth());
        if (h > maxH) { h = maxH; w = (int) ((long) h * photo.getWidth() / photo.getHeight()); }

        stage = new FrameLayout(act);
        stage.setClipChildren(true);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER_HORIZONTAL);
        slp.topMargin = dp(44);
        panel.addView(stage, slp);

        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        int pw = Math.min(photo.getWidth(), 1600);
        iv.setImageBitmap(Bitmap.createScaledBitmap(photo, pw, pw * photo.getHeight() / photo.getWidth(), true));
        stage.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(act);
        hint.setText("点「加字」添加文字，按住文字拖动，点文字可修改");
        hint.setTextColor(Color.parseColor("#BBBBBB"));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, new FrameLayout.LayoutParams(-1, dp(44)));

        LinearLayout bar = new LinearLayout(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), 0);
        Button cancel = btn("放弃", "#333333");
        cancel.setOnClickListener(v -> confirmDiscard());
        Button add = btn("Aa 加字", "#333333");
        add.setOnClickListener(v -> editSticker(null));
        Button save = btn("保存", "#2F5BFF");
        save.setOnClickListener(v -> save());
        bar.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, dp(48), 1);
        mid.leftMargin = dp(10);
        mid.rightMargin = dp(10);
        bar.addView(add, mid);
        bar.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(80), Gravity.BOTTOM);
        blp.bottomMargin = dp(12);
        panel.addView(bar, blp);

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void confirmDiscard() {
        new AlertDialog.Builder(act)
                .setMessage("放弃这张照片？")
                .setPositiveButton("放弃", (d, w) -> close(true))
                .setNegativeButton("继续编辑", null)
                .show();
    }

    private void close(boolean cancelled) {
        root.removeView(panel);
        if (cancelled) onCancel.run();
    }

    // ---------- 文字贴纸 ----------
    private void editSticker(TextView existing) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText in = new EditText(act);
        in.setHint("输入文字，可换行");
        if (existing != null) in.setText(existing.getText());
        box.addView(in);

        final int[] color = {existing == null ? Color.WHITE : existing.getCurrentTextColor()};
        final float[] size = {existing == null ? 24f : pxToSp(existing.getTextSize())};

        LinearLayout colors = new LinearLayout(act);
        colors.setPadding(0, dp(10), 0, dp(4));
        for (int i = 0; i < COLORS.length; i++) {
            final int c = COLORS[i];
            View dot = new View(act);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(dp(2), Color.parseColor("#888888"));
            dot.setBackground(g);
            dot.setContentDescription(COLOR_NAMES[i]);
            dot.setOnClickListener(v -> {
                color[0] = c;
                for (int k = 0; k < colors.getChildCount(); k++) colors.getChildAt(k).setScaleX(1f);
                for (int k = 0; k < colors.getChildCount(); k++) colors.getChildAt(k).setScaleY(1f);
                v.setScaleX(1.3f);
                v.setScaleY(1.3f);
            });
            if (c == color[0]) { dot.setScaleX(1.3f); dot.setScaleY(1.3f); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.rightMargin = dp(12);
            colors.addView(dot, lp);
        }
        box.addView(colors);

        LinearLayout sizes = new LinearLayout(act);
        TextView sizeLabel = new TextView(act);
        sizeLabel.setText("字号 " + (int) size[0]);
        sizeLabel.setTextSize(14);
        Button minus = new Button(act);
        minus.setText("－");
        Button plus = new Button(act);
        plus.setText("＋");
        minus.setOnClickListener(v -> { size[0] = Math.max(12, size[0] - 4); sizeLabel.setText("字号 " + (int) size[0]); });
        plus.setOnClickListener(v -> { size[0] = Math.min(72, size[0] + 4); sizeLabel.setText("字号 " + (int) size[0]); });
        sizes.setGravity(Gravity.CENTER_VERTICAL);
        sizes.addView(sizeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        sizes.addView(minus, new LinearLayout.LayoutParams(dp(56), -2));
        sizes.addView(plus, new LinearLayout.LayoutParams(dp(56), -2));
        box.addView(sizes);

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(existing == null ? "加字" : "修改文字")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    String t = in.getText().toString();
                    if (t.trim().isEmpty()) {
                        if (existing != null) removeSticker(existing);
                        return;
                    }
                    TextView tv = existing != null ? existing : newSticker();
                    tv.setText(t);
                    tv.setTextColor(color[0]);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size[0]);
                    applyShadow(tv, color[0]);
                })
                .setNegativeButton("取消", null);
        if (existing != null) b.setNeutralButton("删除", (d, w) -> removeSticker(existing));
        b.show();
    }

    private TextView newSticker() {
        TextView tv = new TextView(act);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(4), dp(2), dp(4), dp(2));
        tv.setIncludeFontPadding(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        stage.addView(tv, lp);
        tv.setX(stage.getWidth() * 0.25f);
        tv.setY(stage.getHeight() * 0.35f);
        tv.setOnTouchListener(new Drag());
        stickers.add(tv);
        return tv;
    }

    private void removeSticker(TextView tv) {
        stage.removeView(tv);
        stickers.remove(tv);
    }

    private void applyShadow(TextView tv, int color) {
        // 马克式：彩色字不加阴影，白字只加一圈很窄的淡阴影，避免字边发虚
        if (Color.luminance(color) > 0.8f) tv.setShadowLayer(1.2f, 0, 1f, Color.argb(110, 0, 0, 0));
        else tv.setShadowLayer(0, 0, 0, 0);
    }

    /** 按住拖动；几乎没动就当作点击，打开修改。 */
    private class Drag implements View.OnTouchListener {
        float dx, dy, sx, sy;
        boolean moved;
        final int slop = ViewConfiguration.get(act).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dx = v.getX() - e.getRawX();
                    dy = v.getY() - e.getRawY();
                    sx = e.getRawX();
                    sy = e.getRawY();
                    moved = false;
                    v.bringToFront();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - sx) > slop || Math.abs(e.getRawY() - sy) > slop) moved = true;
                    if (moved) {
                        float nx = e.getRawX() + dx, ny = e.getRawY() + dy;
                        // 允许超出一点，但至少留一半在照片里
                        nx = Math.max(-v.getWidth() / 2f, Math.min(stage.getWidth() - v.getWidth() / 2f, nx));
                        ny = Math.max(-v.getHeight() / 2f, Math.min(stage.getHeight() - v.getHeight() / 2f, ny));
                        v.setX(nx);
                        v.setY(ny);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) editSticker((TextView) v);
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---------- 保存 ----------
    private void save() {
        float scale = photo.getWidth() / (float) stage.getWidth();
        Canvas c = new Canvas(photo);
        for (TextView tv : stickers) {
            Layout l = tv.getLayout();
            if (l == null) continue;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
            p.setHinting(Paint.HINTING_OFF);
            p.setTypeface(tv.getTypeface());
            p.setTextSize(tv.getTextSize() * scale);
            p.setColor(tv.getCurrentTextColor());
            if (tv.getShadowRadius() > 0)
                p.setShadowLayer(Math.max(1f, tv.getShadowRadius() * scale * 0.6f), 0, Math.max(1f, tv.getShadowDy() * scale * 0.6f), tv.getShadowColor());
            float left = (tv.getX() + tv.getPaddingLeft()) * scale;
            float top = (tv.getY() + tv.getPaddingTop()) * scale;
            CharSequence text = tv.getText();
            for (int i = 0; i < l.getLineCount(); i++) {
                int st = l.getLineStart(i), en = l.getLineEnd(i);
                String line = text.subSequence(st, en).toString().replace("\n", "");
                c.drawText(line, left + l.getLineLeft(i) * scale, top + l.getLineBaseline(i) * scale, p);
            }
        }
        close(false);
        onSave.save(photo);
    }

    private Button btn(String s, String bg) {
        Button b = new Button(act);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(bg));
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setStateListAnimator(null);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }

    private float pxToSp(float px) {
        return px / act.getResources().getDisplayMetrics().scaledDensity;
    }
}
