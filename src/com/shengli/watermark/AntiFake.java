package com.shengli.watermark;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 防伪码：用本机随机密钥对「原图哈希 + 时间 + 经纬度 + 地址」做 HMAC-SHA256，取 60 位转 12 位码。
 * 只在真实定位、未手动修改时生成；记录保存在本机，可在应用里按码查验。
 */
public final class AntiFake {
    private static final String ALPHA = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PREF = "anti_fake";

    private AntiFake() { }

    private static byte[] key(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String k = sp.getString("key", null);
        if (k == null) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            k = Base64.encodeToString(b, Base64.NO_WRAP);
            sp.edit().putString("key", k).apply();
        }
        return Base64.decode(k, Base64.NO_WRAP);
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String code(Context ctx, String photoHash, long time, double lat, double lng, String address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(ctx), "HmacSHA256"));
            String msg = photoHash + "|" + time + "|" + String.format(Locale.US, "%.6f,%.6f", lat, lng) + "|" + address;
            byte[] out = mac.doFinal(msg.getBytes("UTF-8"));
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (out[i] & 0xff);
            v >>>= 4; // 60 位
            char[] cs = new char[12];
            for (int i = 11; i >= 0; i--) {
                cs[i] = ALPHA.charAt((int) (v & 31));
                v >>>= 5;
            }
            return new String(cs);
        } catch (Exception e) {
            return "";
        }
    }

    public static void record(Context ctx, String code, String info) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("c_" + code, info).apply();
    }

    public static String lookup(Context ctx, String code) {
        String c = code.trim().toUpperCase(Locale.US).replace(" ", "");
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("c_" + c, null);
    }
}
