# 水印相机

开源、无广告、无会员的安卓工作打卡水印相机。

应用内直接取景，水印实时叠在取景画面上；点水印或「编辑文字」就能改地点和文字，按快门即保存：

- 左下角：方块时间（时:分:秒）、日期与星期、黄色分隔线、定位图标 + 地点、经纬度（WGS84，可选）、自定义文字（最多 5 行）、校验状态
- 右下角：品牌标识、「真实时间」标签、12 位防伪码
- 地点可手动修改，也可一键恢复真实地址；文字设置会记住，下次打开还在
- 应用内可按码查验本机拍摄记录

## 防伪码规则

- 时间取相机拍摄时间，不能修改。
- 只有「真实定位 + 地址未修改」的照片才生成防伪码；手动改过地址会标「位置已手动修改」，不出码。
- 检测到模拟定位时忽略该位置。
- 防伪码 = 本机随机密钥对「原图哈希 + 时间 + 经纬度 + 地址」做 HMAC-SHA256 后取 60 位，记录只保存在本机，可在应用里查验。

处理后的照片保存在相册的 `Pictures/水印相机` 目录。

## 安装

直接下载 [release/shengli-watermark.apk](release/shengli-watermark.apk)，在手机上安装（需允许「安装未知来源应用」）。

要求 Android 10 及以上。

## 权限

| 权限 | 用途 |
| --- | --- |
| 相机 | 应用内取景拍照 |
| 定位 | 水印里的地点和经纬度 |
| 网络 | 系统 Geocoder 反查地址 |

本应用不申请存储权限，不含任何广告、统计或推送 SDK，不上传照片。

## 说明

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
