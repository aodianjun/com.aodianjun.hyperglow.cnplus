# Device Compatibility Matrix

# English / 英文

Status: community-maintained device compatibility ledger

## Purpose

HyperGlow resolves capabilities from exact symbol probes, not from a device whitelist: unknown
profiles remain fail-closed (`docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md`). This matrix records which
devices have actually been verified, which capability set resolved, and which probes were missing,
so compatibility issues can be triaged against evidence instead of guesswork.

A row in this table is a claim, not a promise. Every row carries an evidence label as defined in
`docs/STYLE_GUIDE.md` section 12:

- `device-verified`: the named behaviors were exercised on the named build with visual/log evidence.
- `device-smoke-tested`: the module loaded and basic flows survived.
- `trace-observed`: supported by runtime logs only, not necessarily visually confirmed.

## How to add a row

1. Generate a diagnostic report on the device (HyperGlow → Report a problem). The report's product
   metadata contains everything this matrix needs: model/device/product, package versions
   (HyperGlow / SystemUI / Xiaomi AOD), profile state, raw symbol probes, and resolved capabilities.
2. Fill one row per device **and** SystemUI/AOD version combination. A system update that changes
   the SystemUI or Xiaomi AOD version is a new row.
3. Record missing probes exactly as reported (`rawSymbolProbes` entries that did not resolve).
4. Cite the source: your own verification, a linked issue, or a diagnostic report ID.

## Matrix

| Device (codename) | HyperOS | SystemUI | Xiaomi AOD | Profile state | Resolved capabilities | Missing probes | Evidence | Source | Date | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| Redmi K80 Pro (`miro`) | HyperOS 3 | 16.03.251211.r (202501210) | DEV-2327.0.0.1-03022115 (22327001) | available | full set (capability protocol v2 report) | none | device-verified | maintainer daily driver | 2026-09-25 | Reference device behind every behavior spec. |

No other devices are verified yet. Until a row exists for a device, expect fail-closed behavior:
the module installs but resolves no runtime capabilities.

## Triaging compatibility issues

- Ask for a diagnostic report first, then map its profile state and missing probes onto this matrix.
- All probes resolve but behavior differs → behavior bug against the governing spec section, not a
  compatibility gap.
- Probes are missing → the missing symbol names belong in the issue; they are what tell the
  maintainer which HyperOS/SystemUI change to investigate.

---

# 中文 / Chinese

状态：社区维护的机型兼容台账

## 用途

HyperGlow 的能力来自精确符号探测，而不是机型白名单：未知 profile 一律 fail-closed
（`docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md`）。本矩阵记录哪些机型被真实验证过、解析出了哪些能力、
缺了哪些探针，让兼容性 issue 可以凭证据分诊，而不是靠猜。

表中的一行是一个"声明"，不是"承诺"。每一行都必须带 `docs/STYLE_GUIDE.md` 第 12 节定义的证据标签：
`device-verified`（所命名行为在所命名构建上执行过，有视觉/日志证据）、`device-smoke-tested`
（模块加载且基本流程存活）、`trace-observed`（仅有运行时日志支持）。

## 如何添加一行

1. 在目标设备上生成诊断报告（HyperGlow → 报告问题）。报告的 product 元数据包含矩阵所需的全部
   内容：model/device/product、包版本（HyperGlow / SystemUI / Xiaomi AOD）、profile 状态、
   原始符号探针与已解析能力。
2. 每个「机型 + SystemUI/AOD 版本」组合一行。改变 SystemUI 或 AOD 版本的系统更新算新的一行。
3. 按报告原样记录缺失探针（`rawSymbolProbes` 中未命中的项）。
4. 注明来源：自己的验证、关联 issue 或诊断报告 ID。

## 矩阵

| 机型（代号） | HyperOS | SystemUI | Xiaomi AOD | Profile 状态 | 已解析能力 | 缺失探针 | 证据 | 来源 | 日期 | 备注 |
|---|---|---|---|---|---|---|---|---|---|---|
| Redmi K80 Pro (`miro`) | HyperOS 3 | 16.03.251211.r (202501210) | DEV-2327.0.0.1-03022115 (22327001) | available | 全集（capability 协议 v2 报告） | 无 | device-verified | 维护者日常用机 | 2026-09-25 | 全部行为规范背后的参考机型。 |

尚无其它已验证机型。在某个机型有对应行之前，预期行为是 fail-closed：模块可以安装，但解析不出
任何运行时能力。

## 兼容性 issue 分诊

- 先要诊断报告，再把它的 profile 状态与缺失探针映射到本矩阵。
- 探针全部命中但行为不同 → 按 spec 对应章节提行为 bug，不是兼容性缺口。
- 探针缺失 → issue 里要带缺失符号名；这正是维护者定位 HyperOS/SystemUI 变更的线索。
