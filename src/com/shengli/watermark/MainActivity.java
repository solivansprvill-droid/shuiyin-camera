package com.shengli.watermark;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 胜利水印相机：拍照 → 预览编辑水印 → 保存到相册。无广告，无会员。 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_LOC = 1002;
    private static final int MAX_TEXTS = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // 定位
    private double lat = Double.NaN, lng = Double.NaN;
    private long fixTime = 0;
    private String geoAddress = "";      // 系统反查得到的真实地址
    private boolean addressTouched = false;

    // 控件
    private TextView gpsText;
    private EditText addressInput;
    private CheckBox addrBox, coordBox;
    private LinearLayout textsBox;
    private final List<EditText> texts = new ArrayList<>();
    private ImageView preview;
    private LinearLayout editBar;
    private Button shotBtn;

    // 当前照片
    private File captureFile;
    private Bitmap previewBase;
    private long photoTime;
    private String photoHash = "";
    private boolean suppressWatch = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        requestLocationPermission();
    }

    // ================= 界面 =================
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        root.setBackgroundColor(Color.parseColor("#F4F5F7"));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("胜利水印相机");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#2F5BFF"));
        root.addView(title);
        TextView sub = label("真实时间水印 · 无广告 · 无会员", 12, "#8A8F99");
        sub.setPadding(0, dp(2), 0, dp(12));
        root.addView(sub);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setVisibility(View.GONE);
        root.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.HORIZONTAL);
        editBar.setVisibility(View.GONE);
        editBar.setPadding(0, dp(8), 0, dp(4));
        Button discard = button("重拍", "#E6E8EE", "#333333");
        discard.setOnClickListener(v -> { clearPhoto(); takePhoto(); });
        Button save = button("保存到相册", "#2F5BFF", "#FFFFFF");
        save.setOnClickListener(v -> saveCurrent());
        editBar.addView(discard, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 2);
        sp.leftMargin = dp(10);
        editBar.addView(save, sp);
        root.addView(editBar);

        // 地点卡片
        LinearLayout loc = card(root);
        loc.addView(label("地点", 15, "#222222"));
        gpsText = label("定位：获取中…", 12, "#666666");
        gpsText.setPadding(0, dp(4), 0, dp(4));
        loc.addView(gpsText);
        addressInput = new EditText(this);
        addressInput.setHint("地址（可手动修改）");
        addressInput.setTextSize(15);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        addressInput.addTextChangedListener(new Watch(() -> { addressTouched = true; refresh(); }));
        loc.addView(addressInput);
        LinearLayout locBtns = new LinearLayout(this);
        Button relocate = button("重新定位", "#E8EEFF", "#2F5BFF");
        relocate.setOnClickListener(v -> readLocation());
        Button restore = button("恢复真实地址", "#E8EEFF", "#2F5BFF");
        restore.setOnClickListener(v -> restoreAddress());
        locBtns.addView(relocate, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
        rp.leftMargin = dp(8);
        locBtns.addView(restore, rp);
        loc.addView(locBtns);
        TextView tip = label("手动改过的地址会在水印上标「位置已手动修改」，且不生成防伪码。", 11, "#A0A4AD");
        tip.setPadding(0, dp(6), 0, 0);
        loc.addView(tip);

        // 显示项
        LinearLayout show = card(root);
        show.addView(label("水印显示", 15, "#222222"));
        addrBox = check("地点", true);
        coordBox = check("经纬度（WGS84）", false);
        show.addView(addrBox);
        show.addView(coordBox);

        // 加字
        LinearLayout tx = card(root);
        tx.addView(label("加字", 15, "#222222"));
        textsBox = new LinearLayout(this);
        textsBox.setOrientation(LinearLayout.VERTICAL);
        tx.addView(textsBox);
        Button add = button("+ 添加一行文字", "#E8EEFF", "#2F5BFF");
        add.setOnClickListener(v -> addTextLine(""));
        tx.addView(add);
        addTextLine("");

        shotBtn = button("拍照", "#2F5BFF", "#FFFFFF");
        shotBtn.setTextSize(18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.topMargin = dp(14);
        root.addView(shotBtn, lp);
        shotBtn.setOnClickListener(v -> takePhoto());

        Button verify = button("查验防伪码", "#FFFFFF", "#2F5BFF");
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.topMargin = dp(8);
        root.addView(verify, vp);
        verify.setOnClickListener(v -> showVerify());

        return scroll;
    }

    private void addTextLine(String value) {
        if (texts.size() >= MAX_TEXTS) { toast("最多 " + MAX_TEXTS + " 行"); return; }
        LinearLayout row = new LinearLayout(this);
        EditText e = new EditText(this);
        e.setHint("例如：项目名称 / 施工内容 / 打卡人");
        e.setTextSize(15);
        e.setText(value);
        e.addTextChangedListener(new Watch(this::refresh));
        Button del = button("删除", "#F4F5F7", "#999999");
        row.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(del, new LinearLayout.LayoutParams(-2, -2));
        del.setOnClickListener(v -> { textsBox.removeView(row); texts.remove(e); refresh(); });
        texts.add(e);
        textsBox.addView(row);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(12));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        parent.addView(c, lp);
        return c;
    }

    private TextView label(String s, int size, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(Color.parseColor(color));
        if (size >= 15) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private CheckBox check(String s, boolean on) {
        CheckBox cb = new CheckBox(this);
        cb.setText(s);
        cb.setChecked(on);
        cb.setOnCheckedChangeListener((v, c) -> refresh());
        return cb;
    }

    private Button button(String s, String bg, String fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(fg));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(bg));
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setStateListAnimator(null);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private class Watch implements TextWatcher {
        private final Runnable r;
        Watch(Runnable r) { this.r = r; }
        public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        public void onTextChanged(CharSequence s, int a, int b, int c) { }
        public void afterTextChanged(Editable s) { if (!suppressWatch) r.run(); }
    }

    // ================= 定位 =================
    private void requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        } else {
            readLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_LOC) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) readLocation();
            else gpsText.setText("定位：未授权，可手动填写地址（不生成防伪码）");
        }
    }

    @SuppressWarnings("MissingPermission")
    private void readLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission();
            return;
        }
        gpsText.setText("定位：获取中…");
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        if (best != null && System.currentTimeMillis() - best.getTime() < 2 * 60 * 1000) {
            applyLocation(best);
            return;
        }
        if (best != null) applyLocation(best);
        try {
            String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            lm.requestSingleUpdate(prov, this::applyLocation, Looper.getMainLooper());
            ui.postDelayed(() -> {
                if (Double.isNaN(lat)) gpsText.setText("定位：暂无信号，可在室外重试");
            }, 10000);
        } catch (Exception e) {
            if (best == null) gpsText.setText("定位：不可用");
        }
    }

    private void applyLocation(Location l) {
        if (l.isFromMockProvider()) {
            gpsText.setText("定位：检测到模拟位置，已忽略");
            return;
        }
        lat = l.getLatitude();
        lng = l.getLongitude();
        fixTime = l.getTime();
        updateGpsText();
        reverseGeocode();
        refresh();
    }

    private void updateGpsText() {
        String s = "定位：" + Watermark.coords(lat, lng);
        s += "\n" + (geoAddress.isEmpty() ? "地址反查中…（查不到时可手动填写）" : "真实地址：" + geoAddress);
        gpsText.setText(s);
    }

    private void reverseGeocode() {
        final double qa = lat, qb = lng;
        new Thread(() -> {
            String addr = "";
            try {
                android.location.Geocoder g = new android.location.Geocoder(this, Locale.CHINA);
                List<android.location.Address> list = g.getFromLocation(qa, qb, 1);
                if (list != null && !list.isEmpty()) addr = format(list.get(0));
            } catch (Exception ignored) { }
            final String a = addr;
            ui.post(() -> {
                geoAddress = a;
                if (!addressTouched || addressInput.getText().toString().trim().isEmpty()) setAddressSilently(a);
                updateGpsText();
                refresh();
            });
        }).start();
    }

    private static String format(android.location.Address a) {
        StringBuilder sb = new StringBuilder();
        String[] ps = {a.getAdminArea(), a.getLocality(), a.getSubAdminArea(), a.getSubLocality(), a.getThoroughfare()};
        for (String p : ps) if (p != null && sb.indexOf(p) < 0) sb.append(p);
        String f = a.getFeatureName();
        if (f != null && sb.indexOf(f) < 0) sb.append("·").append(f);
        if (sb.length() == 0 && a.getAddressLine(0) != null) sb.append(a.getAddressLine(0));
        return sb.toString();
    }

    private void setAddressSilently(String a) {
        suppressWatch = true;
        addressInput.setText(a);
        suppressWatch = false;
        addressTouched = false;
    }

    private void restoreAddress() {
        if (geoAddress.isEmpty()) { toast("还没有真实地址，请先重新定位"); return; }
        setAddressSilently(geoAddress);
        refresh();
    }

    // ================= 拍照 =================
    private void takePhoto() {
        try {
            captureFile = new File(getCacheDir(), "cap_" + System.currentTimeMillis() + ".jpg");
            Uri uri = PhotoProvider.uriFor(captureFile.getName());
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            toast("无法启动相机：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAMERA) return;
        if (res == Activity.RESULT_OK && captureFile != null && captureFile.length() > 0) {
            final File f = captureFile;
            final long resultTime = System.currentTimeMillis();
            new Thread(() -> loadCaptured(f, resultTime)).start();
        } else if (captureFile != null) {
            captureFile.delete();
        }
    }

    private void loadCaptured(File f, long fallbackTime) {
        try {
            byte[] bytes = readAll(f);
            String hash = AntiFake.sha256Hex(bytes);
            long t = exifTime(f.getAbsolutePath(), fallbackTime);
            Bitmap small = decode(f.getAbsolutePath(), 1440);
            ui.post(() -> {
                photoHash = hash;
                photoTime = t;
                previewBase = small;
                preview.setVisibility(View.VISIBLE);
                editBar.setVisibility(View.VISIBLE);
                shotBtn.setText("再拍一张");
                refresh();
            });
        } catch (Exception e) {
            ui.post(() -> toast("读取照片失败"));
        }
    }

    private void clearPhoto() {
        if (captureFile != null) captureFile.delete();
        captureFile = null;
        previewBase = null;
        preview.setImageDrawable(null);
        preview.setVisibility(View.GONE);
        editBar.setVisibility(View.GONE);
        shotBtn.setText("拍照");
    }

    /** 时间取相机写入的拍摄时间，与系统时间相差过大时用系统时间。 */
    private long exifTime(String path, long fallback) {
        try {
            ExifInterface ex = new ExifInterface(path);
            String s = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (s != null) {
                long t = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s).getTime();
                if (Math.abs(t - fallback) < 10 * 60 * 1000) return t;
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    // ================= 水印 =================
    private WatermarkData currentData() {
        WatermarkData d = new WatermarkData();
        d.timeMillis = photoTime == 0 ? System.currentTimeMillis() : photoTime;
        String addr = addressInput.getText().toString().trim().replace("\n", " ");
        d.address = addr;
        d.lat = lat;
        d.lng = lng;
        d.showAddress = addrBox.isChecked();
        d.showCoords = coordBox.isChecked();
        for (EditText e : texts) d.lines.add(e.getText().toString());

        boolean hasFix = !Double.isNaN(lat) && Math.abs(d.timeMillis - fixTime) < 15 * 60 * 1000;
        boolean addrReal = addr.equals(geoAddress) || (geoAddress.isEmpty() && addr.isEmpty());
        if (!hasFix) {
            d.verified = false;
            d.statusNote = addr.isEmpty() ? "未获取定位" : "位置为手动填写";
        } else if (!addrReal) {
            d.verified = false;
            d.statusNote = "位置已手动修改";
        } else {
            d.verified = true;
            d.code = AntiFake.code(this, photoHash, d.timeMillis, lat, lng, addr);
        }
        return d;
    }

    private void refresh() {
        if (previewBase == null || preview == null) return;
        Bitmap out = previewBase.copy(Bitmap.Config.ARGB_8888, true);
        Watermark.draw(new Canvas(out), out.getWidth(), out.getHeight(), currentData());
        preview.setImageBitmap(out);
    }

    private void saveCurrent() {
        if (captureFile == null || !captureFile.exists()) { toast("请先拍照"); return; }
        final WatermarkData d = currentData();
        final File f = captureFile;
        toast("正在保存…");
        new Thread(() -> {
            try {
                Bitmap full = decode(f.getAbsolutePath(), 4096);
                if (!full.isMutable()) full = full.copy(Bitmap.Config.ARGB_8888, true);
                Watermark.draw(new Canvas(full), full.getWidth(), full.getHeight(), d);
                Uri u = saveToGallery(full, d.timeMillis);
                if (u != null && d.verified) {
                    AntiFake.record(this, d.code,
                            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(d.timeMillis)
                                    + "\n" + d.address + "\n" + Watermark.coords(d.lat, d.lng));
                }
                full.recycle();
                ui.post(() -> {
                    if (u != null) { toast("已保存到相册"); clearPhoto(); }
                    else toast("保存失败");
                });
            } catch (OutOfMemoryError | Exception e) {
                ui.post(() -> toast("保存失败：" + e.getMessage()));
            }
        }).start();
    }

    private Bitmap decode(String path, int maxSide) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, b);
        int sample = 1;
        while (Math.max(b.outWidth, b.outHeight) / sample > maxSide) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inMutable = true;
        Bitmap bmp = BitmapFactory.decodeFile(path, o);
        int deg = 0;
        try {
            int or = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        } catch (Exception ignored) { }
        if (deg != 0 && bmp != null) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        return bmp;
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        }
        return buf;
    }

    private Uri saveToGallery(Bitmap bmp, long t) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "胜利水印_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(t) + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.DATE_TAKEN, t);
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/胜利水印相机");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return null;
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            return null;
        }
        return uri;
    }

    // ================= 查验 =================
    private void showVerify() {
        EditText in = new EditText(this);
        in.setHint("输入照片右下角的 12 位防伪码");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this)
                .setTitle("查验防伪码")
                .setView(in)
                .setPositiveButton("查验", (dlg, w) -> {
                    String r = AntiFake.lookup(this, in.getText().toString());
                    new AlertDialog.Builder(this)
                            .setTitle(r == null ? "本机无此记录" : "记录一致")
                            .setMessage(r == null ? "这个码不是本机拍摄生成的，或已清除应用数据。" : "拍摄记录：\n" + r)
                            .setPositiveButton("好", null).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
