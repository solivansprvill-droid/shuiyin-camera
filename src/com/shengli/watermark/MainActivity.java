package com.shengli.watermark;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
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
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
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
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/** 胜利水印相机：调用系统相机拍照，叠加打卡水印后保存到相册。无广告，无会员。 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_LOC = 1002;

    private EditText titleInput, personInput, noteInput;
    private CheckBox coordBox, addrBox;
    private TextView locText;
    private ImageView preview;

    private Uri captureUri;
    private File captureFile;
    private double lat = Double.NaN, lng = Double.NaN;
    private String address = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestLocationPermission();
    }

    // ---------- UI（用代码构建，避免额外资源） ----------
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, p, p, p);
        root.setBackgroundColor(Color.parseColor("#F7F7F8"));
        scroll.addView(root);

        TextView header = new TextView(this);
        header.setText("胜利水印相机");
        header.setTextSize(24);
        header.setTextColor(Color.parseColor("#DC2626"));
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(header);

        TextView sub = new TextView(this);
        sub.setText("拍照自动加时间、地点、经纬度水印 · 无广告无会员");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#888888"));
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        titleInput = field(root, "标题", "工作打卡");
        personInput = field(root, "打卡人（可选）", "");
        noteInput = field(root, "备注（可选）", "");

        addrBox = new CheckBox(this); addrBox.setText("显示地址"); addrBox.setChecked(true);
        coordBox = new CheckBox(this); coordBox.setText("显示经纬度"); coordBox.setChecked(true);
        root.addView(addrBox); root.addView(coordBox);

        locText = new TextView(this);
        locText.setTextSize(12);
        locText.setTextColor(Color.parseColor("#666666"));
        locText.setPadding(0, dp(4), 0, dp(12));
        locText.setText("定位：获取中…");
        root.addView(locText);

        Button shot = new Button(this);
        shot.setText("拍照打卡");
        shot.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#DC2626"));
        bg.setCornerRadius(dp(14));
        shot.setBackground(bg);
        shot.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        shot.setLayoutParams(lp);
        shot.setOnClickListener(v -> takePhoto());
        root.addView(shot);

        Button refresh = new Button(this);
        refresh.setText("重新定位");
        refresh.setOnClickListener(v -> readLocation());
        root.addView(refresh);

        preview = new ImageView(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(16);
        preview.setLayoutParams(pl);
        preview.setAdjustViewBounds(true);
        root.addView(preview);

        return scroll;
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(12);
        label.setTextColor(Color.parseColor("#555555"));
        label.setPadding(0, dp(8), 0, dp(2));
        parent.addView(label);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(value);
        e.setTextColor(Color.parseColor("#222222"));
        e.setTextSize(15);
        parent.addView(e);
        return e;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------- 定位 ----------
    private void requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        } else {
            readLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LOC) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) readLocation();
            else locText.setText("定位：未授权（水印将不含地点）");
        }
    }

    @SuppressWarnings("MissingPermission")
    private void readLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission();
            return;
        }
        locText.setText("定位：获取中…");
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String prov : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        if (best != null) {
            applyLocation(best);
        } else {
            try {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, loc -> applyLocation(loc), Looper.getMainLooper());
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (Double.isNaN(lat)) locText.setText("定位：暂无信号，可在室外重试");
                }, 8000);
            } catch (Exception e) {
                locText.setText("定位：不可用");
            }
        }
    }

    private void applyLocation(Location l) {
        lat = l.getLatitude();
        lng = l.getLongitude();
        reverseGeocode();
        updateLocText();
    }

    private void updateLocText() {
        String s = String.format(Locale.US, "定位：%.6f, %.6f", lat, lng);
        if (!address.isEmpty()) s += "\n" + address;
        locText.setText(s);
    }

    /** 用系统 Geocoder 反查地址；失败就只留经纬度。 */
    private void reverseGeocode() {
        new Thread(() -> {
            try {
                android.location.Geocoder g = new android.location.Geocoder(this, Locale.CHINA);
                List<android.location.Address> list = g.getFromLocation(lat, lng, 1);
                if (list != null && !list.isEmpty()) {
                    android.location.Address a = list.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (a.getAdminArea() != null) sb.append(a.getAdminArea());
                    if (a.getLocality() != null) sb.append(a.getLocality());
                    if (a.getSubLocality() != null) sb.append(a.getSubLocality());
                    if (a.getThoroughfare() != null) sb.append(a.getThoroughfare());
                    if (a.getFeatureName() != null && sb.indexOf(a.getFeatureName()) < 0) sb.append(a.getFeatureName());
                    address = sb.toString();
                    runOnUiThread(this::updateLocText);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    // ---------- 拍照 ----------
    private void takePhoto() {
        try {
            captureFile = new File(getCacheDir(), "cap_" + System.currentTimeMillis() + ".jpg");
            captureUri = PhotoProvider.uriFor(captureFile.getName());
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(i, REQ_CAMERA);
            } else {
                toast("未找到相机应用");
            }
        } catch (Exception e) {
            toast("无法启动相机：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAMERA && res == Activity.RESULT_OK && captureFile != null && captureFile.exists()) {
            new Thread(this::processAndSave).start();
        }
    }

    private void processAndSave() {
        try {
            String path = captureFile.getAbsolutePath();
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, b);
            int sample = 1;
            while (Math.max(b.outWidth, b.outHeight) / sample > 4096) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inMutable = true;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) { runOnUiThread(() -> toast("读取照片失败")); return; }
            Bitmap out = applyExifRotation(bmp, path);
            if (!out.isMutable()) out = out.copy(Bitmap.Config.ARGB_8888, true);
            Canvas c = new Canvas(out);

            WatermarkData d = new WatermarkData();
            d.timeMillis = System.currentTimeMillis();
            d.title = text(titleInput, "工作打卡");
            d.person = text(personInput, "");
            d.note = text(noteInput, "");
            d.address = address;
            d.lat = lat; d.lng = lng;
            d.showCoords = coordBox.isChecked();
            d.showAddress = addrBox.isChecked();
            Watermark.draw(c, out.getWidth(), out.getHeight(), d);

            Uri saved = saveToGallery(out);
            captureFile.delete();
            final Bitmap show = out;
            runOnUiThread(() -> {
                preview.setImageBitmap(show);
                toast(saved != null ? "已保存到相册" : "保存失败");
            });
        } catch (Exception e) {
            runOnUiThread(() -> toast("处理失败：" + e.getMessage()));
        }
    }

    private Bitmap applyExifRotation(Bitmap bmp, String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int or = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = 0;
            if (or == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg != 0) {
                Matrix m = new Matrix(); m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                return r;
            }
        } catch (Exception ignored) { }
        return bmp;
    }

    private Uri saveToGallery(Bitmap bmp) {
        String name = "胜利水印_" + System.currentTimeMillis() + ".jpg";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
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

    private String text(EditText e, String def) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? def : s;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
