#!/usr/bin/env python3
"""插件 ZIP 自包含性检查（PLUGIN_RELEASE_CONVENTIONS §3/§7）。

对每个 hyperglow-*-plugin.zip 断言：
- ZIP 条目恰为 {manifest.json, classes.dex}；
- dex 若引用 kotlin.jvm.internal.Intrinsics，则必须定义它（= kotlin-stdlib 已随插件打进
  dex）。宿主 release 包经 R8 全量改名，无法按原名提供 kotlin.*，回退双亲不是备选；
- dex 未捆绑插件 API 包（com.lidesheng.hyperlyric.plugin.api.**，由宿主提供）。
"""
import json
import struct
import sys
import zipfile

API_PREFIX = "Lcom/lidesheng/hyperlyric/plugin/api/"
INTRINSICS = "Lkotlin/jvm/internal/Intrinsics;"


def dex_classes(data: bytes) -> list[str]:
    def u32(off: int) -> int:
        return struct.unpack_from("<I", data, off)[0]

    if data[:4] != b"dex\n":
        raise ValueError("not a dex file")
    string_ids_off = u32(0x3C)
    type_ids_off = u32(0x44)
    class_defs_size = u32(0x60)
    class_defs_off = u32(0x64)

    def get_string(idx: int) -> str:
        p = u32(string_ids_off + idx * 4)
        while data[p] & 0x80:
            p += 1
        p += 1
        end = data.index(b"\x00", p)
        return data[p:end].decode("utf-8", "replace")

    return [
        get_string(u32(type_ids_off + u32(class_defs_off + i * 32) * 4))
        for i in range(class_defs_size)
    ]


def check(path: str) -> list[str]:
    with zipfile.ZipFile(path) as z:
        entries = set(z.namelist())
        manifest = json.loads(z.read("manifest.json"))
        dex = z.read("classes.dex")
    problems = []
    if entries != {"manifest.json", "classes.dex"}:
        problems.append(f"zip entries must be exactly [manifest.json, classes.dex], got {sorted(entries)}")
    defined = dex_classes(dex)
    if INTRINSICS.encode() in dex and INTRINSICS not in defined:
        problems.append("dex references kotlin.jvm.internal.Intrinsics but does not define it "
                        "(kotlin-stdlib not bundled)")
    api = [c for c in defined if c.startswith(API_PREFIX)]
    if api:
        problems.append(f"dex bundles plugin-API classes (host-provided): {api[:3]}")
    kotlin = sum(1 for c in defined if c.startswith("Lkotlin/"))
    kotlinx = sum(1 for c in defined if c.startswith("Lkotlinx/"))
    print(f"[{'FAIL' if problems else 'OK'}] {path} id={manifest.get('id')} "
          f"v{manifest.get('version')} classes={len(defined)} kotlin={kotlin} kotlinx={kotlinx}")
    for p in problems:
        print(f"       - {p}")
    return problems


def main(paths: list[str]) -> int:
    bad = [p for p in paths if check(p)]
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: check-plugin-dex.py <zip> [<zip> ...]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
