# 胜利水印相机

开源、无广告、无会员的安卓工作打卡水印相机。

拍照后自动在照片左下角叠加：

- 大号时间（HH:mm）和红色标题标签（默认「工作打卡」，可改）
- 日期与星期
- 地点（系统反查地址，可关闭）
- 经纬度（可关闭）
- 打卡人、备注（可选）

处理后的照片保存在相册的 `Pictures/胜利水印相机` 目录。

## 安装

直接下载 [release/shengli-watermark.apk](release/shengli-watermark.apk)，在手机上安装（需允许「安装未知来源应用」）。

要求 Android 10 及以上。

## 权限

| 权限 | 用途 |
| --- | --- |
| 定位 | 水印里的地点和经纬度 |
| 网络 | 系统 Geocoder 反查地址 |

拍照调用系统相机，本应用不申请相机和存储权限，不含任何广告、统计或推送 SDK，不上传照片。

## 说明

- 时间取手机系统时间，地点取手机真实定位，不提供修改时间或虚拟定位功能。
- 部分国产系统没有谷歌服务时，系统 Geocoder 可能查不到地址，这时水印只显示经纬度。
- 本应用的照片不带任何第三方平台的防伪校验码；如果单位指定了某款打卡 App，请以单位要求为准。

## 从源码构建

不需要 Gradle，只要 JDK 17 和 Android SDK 中的几个文件：

```sh
export ANDROID_JAR=.../platforms/android-34/android.jar
export LAMBDA_JAR=.../build-tools/34.0.0/core-lambda-stubs.jar
export D8_JAR=.../build-tools/34.0.0/lib/d8.jar
export APKSIGNER_JAR=.../build-tools/34.0.0/lib/apksigner.jar
export AAPT2=.../build-tools/34.0.0/aapt2
export ZIPALIGN=.../build-tools/34.0.0/zipalign
./build.sh
```

首次构建会生成本地签名文件 `release.keystore`（已在 `.gitignore` 中忽略）。

## 许可

MIT
