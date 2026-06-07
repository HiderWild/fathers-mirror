# 老爸的镜子（Android 13+ 单 Activity 版）

这是一个最小稳定的前置虚拟镜子应用（单 Activity，无后台服务）：

- 前置摄像头预览
- 数码放大（手势滑杆 + 倍率按钮）
- 拍照并按当前倍率保存
- 镜像开关（预览与拍照同步）
- 保存到 `DCIM/Camera`

## 目录

- `app/src/main/java/com/laobademirror/MainActivity.kt`：主逻辑
- `app/src/main/res/layout/activity_main.xml`：界面
- `app/src/main/AndroidManifest.xml`：权限与入口
- `app/src/main/res/drawable/ic_mirror_logo.xml`：朴素图标

## 使用方式

1. 用 Android Studio 打开本目录。
2. 连接 Android 13+ 手机，运行 `app`。
3. 首次启动同意相机权限。
4. 开始预览后可：
   - 调整缩放（滑杆）
   - 点击 1x / 1.5x / 2x / 3x / Max
   - 开启/关闭镜像
   - 点击拍照保存到 DCIM/Camera

## 说明

- 该版本保留了可控最少的逻辑，优先稳定。
## 建议你后续考虑的补充

- 长按拍照 / 连拍防抖（当前版本已做 1 次拍照防抖）
- 低内存设备的连续拍照回收（当前版本已对保存位图做回收）
- 后台返回后自动重连（当前版本已通过 `onPause/onResume` 处理）
- 明确提示“预览镜像与照片镜像策略”

## 自用 release 自签名（Windows）

- 第 1 步：生成 keystore（用自己的工具链执行）
```
keytool -genkeypair -v -keystore laobadejizzi-release.jks -alias laobadejizzi -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS -storepass <你的仓库密码> -keypass <你的密钥密码> -dname "CN=LaobaDeMirror, OU=Personal, O=Home, L=Shenzhen, ST=GD, C=CN"
```

- 第 2 步：编辑根目录 `release-signing.properties`
```
storeFile=laobadejizzi-release.jks
storePassword=你的仓库密码
keyAlias=laobadejizzi
keyPassword=你的密钥密码
```

- 第 3 步：打 self-signed release 包
```
java -jar .\gradle\wrapper\gradle-wrapper.jar assembleRelease
```
- 输出 APK：
  - `app\\build\\outputs\\apk\\release\\app-release.apk`

说明：
- 如果你的机器没有 `java`/`keytool` 命令，请先配置 JDK 到 PATH（安装 Android Studio 时通常会带 JDK，或单独安装 JDK）。
