# 工作水印相机（Work Watermark Camera）

面向**工程 / 物业 / 巡检工作留证**的相机应用，对标「马克水印相机」，但只保留留证必需的能力：
**一个时间、一个地点、一段自定义内容**，以及让这三样东西事后可核验。

## 定位

| 项 | 值 |
|---|---|
| 应用包名 | `com.watermark.camera` |
| 版本 | `1.0.01`（versionCode 101） |
| minSdk / targetSdk | 24 / 34 |
| 水印模板 | 物业巡检、执勤、工程、考勤、取证、通用、工作汇报 |

## 核心特性

### 1. 水印只有三类信息（不做多余字段）

- **时间日期**——带来源徽标：`可信时间` / `离线推算 09:14` / `设备时间`
- **地点**——中文地址（高德逆地理），成片另存经纬度与海拔
- **自定义内容**——每个模板 2 项（如项目名称、巡检人）

### 2. 可信时间锚定（防"改表"取证）

不依赖第三方授时接口：读取公共 HTTPS 站点的 `Date` 响应头，取最小 RTT 样本得到
`服务器时间 − 设备时间` 的偏移，持久化锚点后用**单调时钟**推进。

- 改系统时钟不改变成片时间，只会被检测并记录（偏差阈值 60 s）
- 断网时退化为「离线推算」（按上次校准推进），并在水印上如实标注
- 拍照过程**永不等待网络**，没网也能拍

### 3. 成片不可篡改的痕迹

- EXIF 写入 GPS 坐标、可信时间、`UserComment` 证据串
- `UserComment` v1 格式：`WC1|WatermarkCamera|时间戳|时钟偏移|时间来源|哈希`
- 哈希绑定「时间 + 来源 + 本机安装实例」，改任何一项都会导致验真失败
- 兼容旧版 3 段格式，老照片不会被误判

详见 [`docs/05-证据链格式.md`]

### 4. 允许移动位置，不允许改内容

水印卡片可在画面内拖动，但**已保存的照片不允许在应用内修改任何内容**。

## 构建

本机构建需要 **JDK 17**（AGP 8.2.2 + Gradle 8.4 不支持更高版本）。

```bash
# 1. 指向 Android SDK（也可用环境变量 ANDROID_HOME）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. Debug 包
./gradlew assembleDebug

# 3. 单元测试
./gradlew testDebugUnitTest

# 4. Release 包（签名从环境变量读取，见下）
./gradlew assembleRelease
```

### Release 签名

签名材料**不入库**。构建 release 前设置环境变量：

```bash
export WORKCAMERA_KEYSTORE_PATH=/secure/path/release.jks
export WORKCAMERA_KEYSTORE_PASSWORD='***'
export WORKCAMERA_KEY_ALIAS='workcamera'
export WORKCAMERA_KEY_PASSWORD='***'
```

未设置口令时 `assembleRelease` 产出未签名 APK（可用于验证构建，不可分发）。

### 首次生成签名证书

```bash
keytool -genkeypair -keystore release.jks -alias workcamera \
  -keyalg RSA -keysize 2048 -validity 9125 \
  -dname "CN=Work Camera, OU=Mobile, O=Work Camera, L=Shanghai, ST=Shanghai, C=CN"
```

## 持续集成

[`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)：

- push / PR 时跑单元测试 → Lint → 构建 Debug 包
- 产物与报告以 artifact 形式附在运行记录上（保留 14 天）

## 文档

| 文档 | 内容 |
|---|---|
| `docs/00-项目记忆.md` | 项目现状、决策记录、构建环境 |
| `docs/01-审计报告.md` | 安全与质量审计（P0/P1/P2） |
| `docs/02-需求对齐.md` | 需求问答记录 |
| `docs/03-需求规格-v1.0.md` | 冻结需求规格（唯一权威） |
| `docs/04-M1-技术方案.md` | 技术方案与任务分解 |
| `docs/05-证据链格式.md` | 可信时间与验真格式 |

## 许可

见 [LICENSE](LICENSE)。