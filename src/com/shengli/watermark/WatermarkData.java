package com.shengli.watermark;

/** 一次打卡水印需要的全部信息。 */
public class WatermarkData {
    public long timeMillis;
    public String title = "工作打卡";
    public String person = "";
    public String note = "";
    public String address = "";
    public double lat = Double.NaN;
    public double lng = Double.NaN;
    public boolean showCoords = true;
    public boolean showAddress = true;
}
