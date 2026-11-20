package com.shengli.watermark;

import java.util.ArrayList;
import java.util.List;

/** 一张照片水印的全部信息。 */
public class WatermarkData {
    public long timeMillis;
    public String address = "";
    public double lat = Double.NaN;
    public double lng = Double.NaN;
    public boolean showAddress = true;
    public boolean showCoords = false;
    public List<String> lines = new ArrayList<>();
    /** true：真实定位 + 真实时间，未修改，可出防伪码。 */
    public boolean verified = false;
    /** verified 为 false 时显示的说明，例如「位置已手动修改」。 */
    public String statusNote = "";
    public String code = "";
}
