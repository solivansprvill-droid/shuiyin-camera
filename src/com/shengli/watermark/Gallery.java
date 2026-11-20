package com.shengli.watermark;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 应用内相册：只显示「水印相机」拍的照片。网格浏览，点开大图可左右滑动、分享、删除。 */
public class Gallery {
    static final String TAG_GRID = "gallery_grid";
    static final String TAG_VIEW = "gallery_view";

    public interface OnChanged { void changed(); }

    private final Activity act;
    private final FrameLayout root;
    private final OnChanged onChanged;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private final List<Uri> uris = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private FrameLayout grid;
    private FrameLayout viewer;

    public Gallery(Activity act, FrameLayout root, OnChanged onChanged) {
        this.act = act;
        this.root = root;
        this.onChanged = onChanged;
    }

    /** 查本应用保存的照片，新的在前。 */
    static List<Uri> query(Activity act, List<Long> outTimes) {
        List<Uri> out = new ArrayList<>();
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN};
        String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = {"Pictures/水印相机%"};
        try (Cursor c = act.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                MediaStore.Images.Media.DATE_TAKEN + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
                if (outTimes != null) outTimes.add(c.getLong(1));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static Bitmap thumb(Activity act, Uri u, int px) {
        try {
            return act.getContentResolver().loadThumbnail(u, new Size(px, px), null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回键：先关大图，再关网格。 */
    public static boolean handleBack(FrameLayout root) {
        View v = root.findViewWithTag(TAG_VIEW);
        if (v != null) { root.removeView(v); return true; }
        v = root.findViewWithTag(TAG_GRID);
        if (v != null) { root.removeView(v); return true; }
        return false;
    }

    public void show() {
        grid = new FrameLayout(act);
        grid.setTag(TAG_GRID);
        grid.setBackgroundColor(Color.parseColor("#111111"));
        grid.setClickable(true);

        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), 0, dp(16), 0);
        Button back = flat("‹ 返回");
        back.setOnClickListener(v -> root.removeView(grid));
        TextView title = new TextView(act);
        title.setText("我的照片");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        top.addView(back, new LinearLayout.LayoutParams(dp(80), dp(52)));
        top.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        top.addView(new View(act), new LinearLayout.LayoutParams(dp(80), dp(52)));
        grid.addView(top, new FrameLayout.LayoutParams(-1, dp(52)));

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.topMargin = dp(52);
        grid.addView(sv, lp);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        fill(list);
    }

    private void fill(LinearLayout list) {
        list.removeAllViews();
        uris.clear();
        times.clear();
        uris.addAll(query(act, times));
        if (uris.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有照片，拍一张试试");
            empty.setTextColor(Color.parseColor("#999999"));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(120), 0, 0);
            list.addView(empty);
            return;
        }
        int gap = dp(2);
        int cell = (act.getResources().getDisplayMetrics().widthPixels - gap * 4) / 3;
        SimpleDateFormat day = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);
        String lastDay = null;
        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < uris.size(); i++) {
            String d = day.format(times.get(i));
            if (!d.equals(lastDay)) {
                lastDay = d;
                TextView h = new TextView(act);
                h.setText(d);
                h.setTextColor(Color.parseColor("#CCCCCC"));
                h.setTextSize(13);
                h.setPadding(dp(10), dp(12), 0, dp(6));
                list.addView(h);
                row = null;
            }
            if (row == null || inRow == 3) {
                row = new LinearLayout(act);
                row.setPadding(gap, 0, gap, gap);
                list.addView(row);
                inRow = 0;
            }
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Color.parseColor("#222222"));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cell, cell);
            p.rightMargin = gap;
            row.addView(iv, p);
            inRow++;
            final int idx = i;
            iv.setOnClickListener(v -> openViewer(idx));
            final Uri u = uris.get(i);
            pool.execute(() -> {
                Bitmap b = thumb(act, u, cell);
                if (b != null) ui.post(() -> iv.setImageBitmap(b));
            });
        }
    }

    // ---------- 大图 ----------
    private int cur;
    private ImageView big;
    private TextView counter;

    private void openViewer(int idx) {
        cur = idx;
        viewer = new FrameLayout(act);
        viewer.setTag(TAG_VIEW);
        viewer.setBackgroundColor(Color.BLACK);
        viewer.setClickable(true);

        big = new ImageView(act);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(big, new FrameLayout.LayoutParams(-1, -1));

        GestureDetector gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(b.getX() - a.getX()) < dp(60) || Math.abs(vx) < Math.abs(vy)) return false;
                step(b.getX() < a.getX() ? 1 : -1);
                return true;
            }
        });
        big.setOnTouchListener((v, e) -> gd.onTouchEvent(e));

        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.argb(120, 0, 0, 0));
        top.setPadding(dp(8), 0, dp(16), 0);
        Button back = flat("‹ 返回");
        back.setOnClickListener(v -> root.removeView(viewer));
        counter = new TextView(act);
        counter.setTextColor(Color.WHITE);
        counter.setGravity(Gravity.CENTER);
        top.addView(back, new LinearLayout.LayoutParams(dp(80), dp(52)));
        top.addView(counter, new LinearLayout.LayoutParams(0, dp(52), 1));
        top.addView(new View(act), new LinearLayout.LayoutParams(dp(80), dp(52)));
        viewer.addView(top, new FrameLayout.LayoutParams(-1, dp(52)));

        LinearLayout bar = new LinearLayout(act);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(24), dp(10), dp(24), dp(10));
        bar.setBackgroundColor(Color.argb(120, 0, 0, 0));
        Button share = pill("分享", "#2F5BFF");
        share.setOnClickListener(v -> share());
        Button del = pill("删除", "#444444");
        del.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(46), 1);
        bp.rightMargin = dp(12);
        bar.addView(share, bp);
        bar.addView(del, new LinearLayout.LayoutParams(0, dp(46), 1));
        viewer.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        load();
    }

    private void step(int d) {
        int n = cur + d;
        if (n < 0 || n >= uris.size()) return;
        cur = n;
        load();
    }

    private void load() {
        counter.setText((cur + 1) + " / " + uris.size());
        final Uri u = uris.get(cur);
        final int want = cur;
        big.setImageBitmap(null);
        pool.execute(() -> {
            Bitmap b = decode(u, 2400);
            ui.post(() -> { if (want == cur && b != null) big.setImageBitmap(b); });
        });
    }

    private Bitmap decode(Uri u, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = act.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > max) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            try (InputStream in = act.getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Exception e) {
            return null;
        }
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM, uris.get(cur));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        act.startActivity(Intent.createChooser(i, "分享照片"));
    }

    private void confirmDelete() {
        new AlertDialog.Builder(act)
                .setMessage("删除这张照片？相册里也会一起删除。")
                .setPositiveButton("删除", (d, w) -> {
                    int n = 0;
                    try { n = act.getContentResolver().delete(uris.get(cur), null, null); } catch (Exception ignored) { }
                    if (n <= 0) {
                        android.widget.Toast.makeText(act, "删除失败，可在系统相册里删除", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    uris.remove(cur);
                    times.remove(cur);
                    onChanged.changed();
                    if (grid != null) {
                        ScrollView sv = (ScrollView) grid.getChildAt(1);
                        fill((LinearLayout) sv.getChildAt(0));
                    }
                    if (uris.isEmpty()) { root.removeView(viewer); return; }
                    if (cur >= uris.size()) cur = uris.size() - 1;
                    load();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Button flat(String s) {
        Button b = new Button(act);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setStateListAnimator(null);
        return b;
    }

    private Button pill(String s, String bg) {
        Button b = flat(s);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(bg));
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }
}
