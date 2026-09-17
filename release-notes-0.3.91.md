> ⚠️ **测试版本请勿下载** — 此版本为预发行测试版，仅供测试与问题排查，请勿用于日常使用。
## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.91-118.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.91-118.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.91 (versionCode 118)【预发行】

### 修复 · 息屏画布随设备旋转不生效（issue #29）

- **根因**：设置页「随设备旋转」开关只写入 `AOD_ROTATE_WITH_DEVICE`、从不写入 `AOD_ROTATION_MODE`；读取端 `normalizeAodRotationMode` 又把它归一回退到 `portrait`，而 `resolveAodRotationStep` 对 `portrait` 恒返回 null，于是监视器以 `portrait` 附着后永不产生旋转步进，画布始终竖屏。
- **修复（读取联动 + 写出默认 + UI 生效）**：
  - 读取端联动：开关已开启但模式仍为默认 `portrait` 时，`effectiveAodRotationMode` 就地视作 `auto`，设备侧放即刻可旋转。
  - 开关写出默认：开启「随设备旋转」时若从未存过有效旋转模式，自动写入默认 `auto`，持久化偏好自此自洽。
  - 旋转模式选项可生效：开关下的「旋转模式」（自动 / 横屏 / 反向横屏）与「旋转稳定时长」等设置会真正落入偏好并驱动旋转行为。
- **补充单测**：`effectiveAodRotationMode` 在开关开/关、已有/未设置模式下的判定。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/117-0.3.90...118-0.3.91