# Work Camera (工作相机) — 修复版工程说明

## 版本
- package: com.watermark.camera
- versionName: 1.0.0 / versionCode: 1
- minSdk 24 / targetSdk 34
- 签名别名: workcamera (CN=Work Camera)

## 本包已包含的修复
1. 拍照保存：CameraViewModel 接通 ProcessPhotoUseCase（不再只 close ImageProxy）
2. 拍照间隔：COOLDOWN_MS = 300（0.3 秒）
3. CameraEvent 实现 UiEvent（编译）
4. fragment_camera 使用 WatermarkOverlayView（避免 ClassCast）
5. 导航容器改为 nav_host_fragment + Activity FragmentManager
6. 水印设置保存前写入姓名/项目/备注
7. 拼图：系统多图选择 GetMultipleContents
8. BitmapDecoder 支持 content://
9. 毛玻璃 drawable + 顶/底栏样式
10. CameraX API 兼容（flashMode Int、AspectRatioStrategy）
11. stopPreview 返回 Unit；CollageEngine Color 非 const
12. buildConfig true；release 默认不 minify（便于调试）
13. 北斗：GnssStatus 监测 + 水印 [北斗] 前缀

## 本机打包（建议 ≥8GB RAM）
```bash
# 1. 生成签名（若尚未生成）
keytool -genkeypair -keystore app/workcamera-release.jks -alias workcamera \
  -keyalg RSA -keysize 2048 -validity 9125 \
  -storepass 'WorkCamera#2026Release' -keypass 'WorkCamera#2026Release' \
  -dname "CN=Work Camera, OU=Mobile, O=Work Camera, L=Shanghai, ST=Shanghai, C=CN"

# 2. local.properties
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 3. 编译
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release.apk
```

## 签名口令（仅内测）
- store/key password: WorkCamera#2026Release
- 上架前请自行更换强口令与正式证书
