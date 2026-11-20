package com.shengli.watermark;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 水印相机：取景画面上实时显示水印，点水印或「编辑」直接改文字，按快门即保存。无广告，无会员。 */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final int MAX_TEXTS = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // 相机
    private TextureView texture;
    private ImageView overlay;
    private FrameLayout frame;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread camThread;
    private Handler camHandler;
    private Size previewSize, jpegSize;
    private int sensorOrientation = 90;
    private boolean busy = false;
    private boolean askedPerms = false;

    // 定位
    private double lat = Double.NaN, lng = Double.NaN;
    private long fixTime = 0;
    private String geoAddress = "";
    private String address = "";
    private boolean addressEdited = false;

    // 水印设置
    private boolean showAddress = true, showCoords = false;
    private final List<String> lines = new ArrayList<>();

    private ImageView thumb;
    private TextView statusBar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        loadSettings();
        setContentView(buildUi());
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPerm(Manifest.permission.CAMERA)) {
            startCamThread();
            if (texture.isAvailable()) openCamera(); else texture.setSurfaceTextureListener(surfaceListener);
        }
        if (!askedPerms && (!hasPerm(Manifest.permission.CAMERA) || !hasPerm(Manifest.permission.ACCESS_FINE_LOCATION))) {
            askedPerms = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMS);
        } else if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            readLocation();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCamThread();
        super.onPause();
    }

    private boolean hasPerm(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != REQ_PERMS) return;
        if (hasPerm(Manifest.permission.CAMERA)) {
            startCamThread();
            if (texture.isAvailable()) openCamera(); else texture.setSurfaceTextureListener(surfaceListener);
        } else {
            toast("需要相机权限才能拍照");
        }
        if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) readLocation();
        else setStatus("定位未授权，可点「编辑」手动填写地址");
    }

    // ================= 界面 =================
    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        int sw = getResources().getDisplayMetrics().widthPixels;
        int fh = sw * 4 / 3;
        frame = new FrameLayout(this);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(sw, fh);
        flp.topMargin = dp(48);
        root.addView(frame, flp);

        texture = new TextureView(this);
        frame.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ImageView(this);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setOnClickListener(v -> openEditor());

        // 顶部栏
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), 0, dp(14), 0);
        TextView name = new TextView(this);
        name.setText("水印相机");
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button verify = smallBtn("查验");
        verify.setOnClickListener(v -> showVerify());
        top.addView(verify);
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(48)));

        statusBar = new TextView(this);
        statusBar.setTextColor(Color.parseColor("#DDDDDD"));
        statusBar.setTextSize(12);
        statusBar.setGravity(Gravity.CENTER);
        statusBar.setText("定位中…");
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(48) + fh + dp(6);
        root.addView(statusBar, slp);

        // 底部操作
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(24), 0, dp(24), 0);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(Color.parseColor("#333333"));
        tb.setCornerRadius(dp(8));
        thumb.setBackground(tb);
        thumb.setClipToOutline(true);
        bottom.addView(thumb, new LinearLayout.LayoutParams(dp(52), dp(52)));
        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        View shutter = new View(this);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setColor(Color.WHITE);
        sd.setStroke(dp(5), Color.parseColor("#888888"));
        shutter.setBackground(sd);
        shutter.setOnClickListener(v -> takePicture());
        bottom.addView(shutter, new LinearLayout.LayoutParams(dp(76), dp(76)));
        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        LinearLayout edit = new LinearLayout(this);
        edit.setOrientation(LinearLayout.VERTICAL);
        edit.setGravity(Gravity.CENTER);
        TextView aa = new TextView(this);
        aa.setText("Aa");
        aa.setTextColor(Color.WHITE);
        aa.setTextSize(22);
        aa.setTypeface(Typeface.DEFAULT_BOLD);
        aa.setGravity(Gravity.CENTER);
        TextView el = new TextView(this);
        el.setText("编辑文字");
        el.setTextColor(Color.WHITE);
        el.setTextSize(11);
        edit.addView(aa);
        edit.addView(el);
        edit.setOnClickListener(v -> openEditor());
        bottom.addView(edit, new LinearLayout.LayoutParams(dp(60), dp(60)));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM);
        blp.bottomMargin = dp(18);
        root.addView(bottom, blp);
        return root;
    }

    private Button smallBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#33FFFFFF"));
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void setStatus(String s) { statusBar.setText(s); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            drawOverlay();
            ui.postDelayed(this, 1000);
        }
    };

    /** 取景框上的实时水印，和成片同一套绘制，比例一致。 */
    private void drawOverlay() {
        int w = frame.getWidth(), h = frame.getHeight();
        if (w <= 0 || h <= 0) return;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Watermark.draw(new Canvas(bm), w, h, buildData(System.currentTimeMillis(), null));
        overlay.setImageBitmap(bm);
    }

    // ================= 编辑文字（在拍照界面里弹出） =================
    private void openEditor() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box);

        box.addView(caption("地点"));
        EditText addrIn = new EditText(this);
        addrIn.setText(address);
        addrIn.setHint("地址");
        addrIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(addrIn);
        Button restore = new Button(this);
        restore.setText("恢复真实定位地址");
        restore.setAllCaps(false);
        restore.setOnClickListener(v -> {
            if (geoAddress.isEmpty()) toast("还没有定位地址");
            else addrIn.setText(geoAddress);
        });
        box.addView(restore);
        TextView tip = caption("手动改过的地址会标「位置已手动修改」，不生成防伪码。");
        tip.setTextSize(11);
        tip.setTypeface(Typeface.DEFAULT);
        box.addView(tip);

        CheckBox addrBox = new CheckBox(this);
        addrBox.setText("显示地点");
        addrBox.setChecked(showAddress);
        CheckBox coordBox = new CheckBox(this);
        coordBox.setText("显示经纬度");
        coordBox.setChecked(showCoords);
        box.addView(addrBox);
        box.addView(coordBox);

        box.addView(caption("文字（每行一条，最多 " + MAX_TEXTS + " 行）"));
        LinearLayout lineBox = new LinearLayout(this);
        lineBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(lineBox);
        List<EditText> edits = new ArrayList<>();
        for (String l : lines) addLine(lineBox, edits, l);
        if (edits.isEmpty()) addLine(lineBox, edits, "");
        Button add = new Button(this);
        add.setText("+ 添加一行");
        add.setAllCaps(false);
        add.setOnClickListener(v -> {
            if (edits.size() >= MAX_TEXTS) toast("最多 " + MAX_TEXTS + " 行");
            else addLine(lineBox, edits, "");
        });
        box.addView(add);

        new AlertDialog.Builder(this)
                .setTitle("编辑水印文字")
                .setView(sv)
                .setPositiveButton("完成", (d, w) -> {
                    String a = addrIn.getText().toString().trim().replace("\n", " ");
                    addressEdited = !a.equals(geoAddress);
                    address = a;
                    showAddress = addrBox.isChecked();
                    showCoords = coordBox.isChecked();
                    lines.clear();
                    for (EditText e : edits) {
                        String s = e.getText().toString().trim();
                        if (!s.isEmpty()) lines.add(s);
                    }
                    saveSettings();
                    drawOverlay();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addLine(LinearLayout parent, List<EditText> edits, String v) {
        LinearLayout row = new LinearLayout(this);
        EditText e = new EditText(this);
        e.setText(v);
        e.setHint("例如：项目名称 / 施工内容 / 打卡人");
        Button del = new Button(this);
        del.setText("删");
        del.setOnClickListener(x -> { parent.removeView(row); edits.remove(e); });
        row.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(del, new LinearLayout.LayoutParams(dp(52), -2));
        parent.addView(row);
        edits.add(e);
    }

    private TextView caption(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private void loadSettings() {
        showAddress = prefs.getBoolean("showAddress", true);
        showCoords = prefs.getBoolean("showCoords", false);
        String l = prefs.getString("lines", "");
        lines.clear();
        for (String s : l.split("\n")) if (!s.trim().isEmpty()) lines.add(s);
    }

    private void saveSettings() {
        prefs.edit().putBoolean("showAddress", showAddress).putBoolean("showCoords", showCoords)
                .putString("lines", String.join("\n", lines)).apply();
    }

    // ================= 水印数据 =================
    private WatermarkData buildData(long time, String photoHash) {
        WatermarkData d = new WatermarkData();
        d.timeMillis = time;
        d.address = address;
        d.lat = lat;
        d.lng = lng;
        d.showAddress = showAddress;
        d.showCoords = showCoords;
        d.lines.addAll(lines);
        boolean hasFix = !Double.isNaN(lat) && Math.abs(time - fixTime) < 15 * 60 * 1000;
        if (!hasFix) {
            d.statusNote = address.isEmpty() ? "未获取定位" : "位置为手动填写";
        } else if (addressEdited) {
            d.statusNote = "位置已手动修改";
        } else {
            d.verified = true;
            d.code = photoHash == null ? "············" : AntiFake.code(this, photoHash, time, lat, lng, address);
        }
        return d;
    }

    // ================= 定位 =================
    @SuppressWarnings("MissingPermission")
    private void readLocation() {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        if (best != null) applyLocation(best);
        try {
            String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            lm.requestSingleUpdate(prov, this::applyLocation, Looper.getMainLooper());
        } catch (Exception ignored) { }
    }

    private void applyLocation(Location l) {
        if (l.isFromMockProvider()) { setStatus("检测到模拟位置，已忽略"); return; }
        lat = l.getLatitude();
        lng = l.getLongitude();
        fixTime = System.currentTimeMillis();
        setStatus("已定位 " + Watermark.coords(lat, lng));
        final double qa = lat, qb = lng;
        new Thread(() -> {
            String a = "";
            try {
                android.location.Geocoder g = new android.location.Geocoder(this, Locale.CHINA);
                List<android.location.Address> list = g.getFromLocation(qa, qb, 1);
                if (list != null && !list.isEmpty()) a = format(list.get(0));
            } catch (Exception ignored) { }
            final String addr = a;
            ui.post(() -> {
                if (!addr.isEmpty()) {
                    geoAddress = addr;
                    if (!addressEdited) address = addr;
                } else if (geoAddress.isEmpty()) {
                    setStatus("已定位，地址反查失败，可点「编辑文字」手动填写");
                }
                drawOverlay();
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

    // ================= Camera2 =================
    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
        public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
        public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
        public void onSurfaceTextureUpdated(SurfaceTexture s) { }
    };

    private void startCamThread() {
        if (camThread != null) return;
        camThread = new HandlerThread("cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
    }

    private void stopCamThread() {
        if (camThread == null) return;
        camThread.quitSafely();
        camThread = null;
        camHandler = null;
    }

    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        if (camera != null || !hasPerm(Manifest.permission.CAMERA)) return;
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null) id = cm.getCameraIdList()[0];
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            jpegSize = pick43(map.getOutputSizes(ImageFormat.JPEG), 4000);
            previewSize = pick43(map.getOutputSizes(SurfaceTexture.class), 1920);
            reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onJpeg, camHandler);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice c) { camera = c; startPreview(); }
                public void onDisconnected(CameraDevice c) { c.close(); camera = null; }
                public void onError(CameraDevice c, int e) { c.close(); camera = null; ui.post(() -> toast("相机打开失败")); }
            }, camHandler);
        } catch (Exception e) {
            toast("相机不可用：" + e.getMessage());
        }
    }

    /** 选最接近 4:3、长边不超过 maxLong 的最大尺寸。 */
    private static Size pick43(Size[] sizes, int maxLong) {
        List<Size> ok = new ArrayList<>();
        for (Size s : sizes) {
            int lo = Math.max(s.getWidth(), s.getHeight()), sh = Math.min(s.getWidth(), s.getHeight());
            if (lo <= maxLong && Math.abs(lo * 3 - sh * 4) <= lo / 50) ok.add(s);
        }
        List<Size> pool = ok.isEmpty() ? Arrays.asList(sizes) : ok;
        return Collections.max(pool, (a, b) -> Long.compare((long) a.getWidth() * a.getHeight(), (long) b.getWidth() * b.getHeight()));
    }

    private void startPreview() {
        try {
            SurfaceTexture st = texture.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface s = new Surface(st);
            final CaptureRequest.Builder rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(s);
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(s, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession cs) {
                    session = cs;
                    try { cs.setRepeatingRequest(rb.build(), null, camHandler); } catch (Exception ignored) { }
                }
                public void onConfigureFailed(CameraCaptureSession cs) { ui.post(() -> toast("预览启动失败")); }
            }, camHandler);
        } catch (CameraAccessException e) {
            toast("预览启动失败");
        }
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
    }

    private void takePicture() {
        if (camera == null || session == null || busy) return;
        busy = true;
        try {
            CaptureRequest.Builder rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            rb.addTarget(reader.getSurface());
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            rb.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
            rb.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            session.capture(rb.build(), null, camHandler);
            frame.animate().alpha(0.3f).setDuration(80).withEndAction(() -> frame.animate().alpha(1f).setDuration(120));
        } catch (Exception e) {
            busy = false;
            toast("拍照失败");
        }
    }

    private void onJpeg(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) { busy = false; return; }
        ByteBuffer buf = img.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        img.close();
        final long t = System.currentTimeMillis();
        final WatermarkData[] holder = new WatermarkData[1];
        // 水印数据在主线程上取，避免和编辑同时改动
        ui.post(() -> {
            holder[0] = buildData(t, AntiFake.sha256Hex(bytes));
            new Thread(() -> process(bytes, holder[0])).start();
        });
    }

    private void process(byte[] jpeg, WatermarkData d) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            int deg = 0;
            try {
                int or = new ExifInterface(new ByteArrayInputStream(jpeg))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                if (or == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
                else if (or == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
                else if (or == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            } catch (Exception ignored) { }
            // 竖屏拍摄但设备没写 EXIF 也没旋转像素时，按传感器方向补转
            if (deg == 0 && bmp.getWidth() > bmp.getHeight()) deg = sensorOrientation;
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap rot = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rot != bmp) bmp.recycle();
                bmp = rot;
            }
            if (!bmp.isMutable()) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true);
            Watermark.draw(new Canvas(bmp), bmp.getWidth(), bmp.getHeight(), d);
            Uri u = saveToGallery(bmp, d.timeMillis);
            if (u != null && d.verified) {
                AntiFake.record(this, d.code,
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(d.timeMillis)
                                + "\n" + d.address + "\n" + Watermark.coords(d.lat, d.lng));
            }
            Bitmap small = Bitmap.createScaledBitmap(bmp, 240, 240 * bmp.getHeight() / bmp.getWidth(), true);
            bmp.recycle();
            ui.post(() -> {
                busy = false;
                if (u != null) { thumb.setImageBitmap(small); toast("已保存到相册"); }
                else toast("保存失败");
            });
        } catch (Throwable e) {
            ui.post(() -> { busy = false; toast("保存失败：" + e.getMessage()); });
        }
    }

    private Uri saveToGallery(Bitmap bmp, long t) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "水印相机_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(t) + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.DATE_TAKEN, t);
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/水印相机");
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
