#!/usr/bin/env python3
"""Assemble HyperGlow word-effect programs into a pack JSON.

HyperGlow effects may carry a sandboxed bytecode program (see
``app/src/main/java/com/eza/hyperglow/effect/EffectProgram.kt`` and
``EffectRuntime.kt``).  This tool is a tiny assembler for that bytecode:

* a program is a list of ``(op, arg, text)`` tuples plus a constants list;
* ``("label", "name")`` marks a jump target and jumps may use a label string
  instead of a numeric instruction index;
* every opcode, field name and limit is validated against the Kotlin
  definitions copied below before JSON is written.

Example
-------
::

    from tools.assemble_effect_program import assemble

    program = assemble(
        [
            ("lineField", None, "text"),
            ("const", 0, None),              # x = 40
            ("const", 1, None),              # y = 0.1 * canvas.height
            ("canvasField", None, "height"),
            ("mul", None, None),
            ("drawText", None, None),        # text, x, y
            ("end", None, None),
        ],
        constants=[40, 0.1],
        max_locals=0,
    )

The generated pack can be written with ``build_pack()`` (the five sample
effects) or with your own ``ProgramBuilder`` instances::

    python tools/assemble_effect_program.py            # write the sample pack
    python tools/assemble_effect_program.py --check    # self-check only
    python tools/assemble_effect_program.py --stdout   # print JSON, do not write
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable, Sequence

# ---------------------------------------------------------------------------
# Whitelists copied from EffectProgram.kt.  Keep these in sync with the app.
# ---------------------------------------------------------------------------

EFFECT_PROGRAM_VERSION = 1
MAX_EFFECT_CONSTANTS = 64
MAX_EFFECT_CODE = 512
MAX_EFFECT_LOCALS = 24
MAX_EFFECT_STACK = 64
MAX_EFFECT_STEPS_PER_FRAME = 100_000
MAX_EFFECT_DRAW_OPS_PER_FRAME = 4_096
MAX_EFFECT_PROGRAM_ESTIMATED_BYTES = 12 * 1024

EFFECT_OPS = frozenset(
    {
        # constants / locals
        "const", "loadLocal", "storeLocal", "pop", "dup", "swap",
        # context
        "globalField", "lineField", "lineCount", "line", "lineAtField",
        "canvasField", "colorField",
        "wordCount", "word", "wordField",
        # arithmetic
        "add", "sub", "mul", "div", "mod", "neg",
        "sin", "cos", "abs", "min", "max", "clamp", "lerp", "pow", "sqrt",
        "floor", "round",
        # comparison / logic
        "lt", "le", "gt", "ge", "eq", "ne", "and", "or", "not",
        # strings
        "stringLength", "charAt", "substring",
        # control flow
        "jump", "jz", "jnz",
        # draw state
        "setColor", "setAlpha", "setTextSize", "setTextAlign",
        "setGradient", "clearGradient", "setShadow", "clearShadow",
        "setStrokeWidth", "setFill", "setStroke",
        "save", "restore", "translate", "rotate", "scale", "clipRect",
        # drawing
        "drawText", "drawRect", "drawCircle", "drawLine",
        "drawRoundRect", "drawOval", "drawArc",
        "pathMove", "pathLine", "pathQuad", "pathCubic", "pathClose", "drawPath",
        "measureText", "end",
    }
)

EFFECT_GLOBAL_FIELDS = frozenset(
    {"time", "wall", "baseline", "textSize", "blockWidth", "blockHeight"}
)
EFFECT_LINE_FIELDS = frozenset(
    {"text", "start", "end", "progress", "index", "x", "width", "baseline", "height"}
)
EFFECT_CANVAS_FIELDS = frozenset({"width", "height"})
EFFECT_COLOR_FIELDS = frozenset({"sung", "unsung", "glow", "accent"})
EFFECT_WORD_FIELDS = frozenset(
    {"text", "x", "width", "start", "end", "progress", "index", "long", "line", "baseline"}
)

FIELD_OPS = {
    "globalField": EFFECT_GLOBAL_FIELDS,
    "lineField": EFFECT_LINE_FIELDS,
    "lineAtField": EFFECT_LINE_FIELDS,
    "canvasField": EFFECT_CANVAS_FIELDS,
    "colorField": EFFECT_COLOR_FIELDS,
    "wordField": EFFECT_WORD_FIELDS,
}

# Mirrors EffectProgram.EFFECT_DRAW_OPS: native draw/measure/state calls that
# count against MAX_EFFECT_DRAW_OPS_PER_FRAME.
EFFECT_DRAW_OPS = frozenset(
    {
        "setColor", "setAlpha", "setTextSize", "setTextAlign",
        "setGradient", "clearGradient", "setShadow", "clearShadow",
        "setStrokeWidth", "setFill", "setStroke",
        "save", "restore", "translate", "rotate", "scale", "clipRect",
        "drawText", "drawRect", "drawCircle", "drawLine",
        "drawRoundRect", "drawOval", "drawArc",
        "pathMove", "pathLine", "pathQuad", "pathCubic", "pathClose", "drawPath",
        "measureText",
    }
)

ARG_OPS = frozenset({"const", "loadLocal", "storeLocal", "jump", "jz", "jnz"})
JUMP_OPS = frozenset({"jump", "jz", "jnz"})

PACK_FORMAT = "hyperglow-word-effect-pack"
PACK_VERSION = 1
MAX_PACK_BYTES = 64 * 1024
MAX_EFFECTS = 32
MAX_NAME_CHARS = 48
EFFECT_ID_RE = re.compile(r"^[a-z0-9_]{1,32}$")

# (pops, pushes) per opcode; used by the static stack-depth check.
STACK_EFFECT: dict[str, tuple[int, int]] = {
    "const": (0, 1),
    "loadLocal": (0, 1),
    "storeLocal": (1, 0),
    "pop": (1, 0),
    "dup": (1, 2),
    "swap": (2, 2),
    "globalField": (0, 1),
    "lineField": (0, 1),
    "lineCount": (0, 1),
    "line": (1, 1),
    "lineAtField": (1, 1),
    "canvasField": (0, 1),
    "colorField": (0, 1),
    "wordCount": (0, 1),
    "word": (1, 1),
    "wordField": (1, 1),
    "add": (2, 1),
    "sub": (2, 1),
    "mul": (2, 1),
    "div": (2, 1),
    "mod": (2, 1),
    "neg": (1, 1),
    "sin": (1, 1),
    "cos": (1, 1),
    "abs": (1, 1),
    "min": (2, 1),
    "max": (2, 1),
    "clamp": (3, 1),
    "lerp": (3, 1),
    "pow": (2, 1),
    "sqrt": (1, 1),
    "floor": (1, 1),
    "round": (1, 1),
    "lt": (2, 1),
    "le": (2, 1),
    "gt": (2, 1),
    "ge": (2, 1),
    "eq": (2, 1),
    "ne": (2, 1),
    "and": (2, 1),
    "or": (2, 1),
    "not": (1, 1),
    "stringLength": (1, 1),
    "charAt": (2, 1),
    "substring": (3, 1),
    "jump": (0, 0),
    "jz": (1, 0),
    "jnz": (1, 0),
    "setColor": (1, 0),
    "setAlpha": (1, 0),
    "setTextSize": (1, 0),
    "setTextAlign": (1, 0),
    # setGradient(x0, y0, x1, y1, color0, color1)
    "setGradient": (6, 0),
    "clearGradient": (0, 0),
    # setShadow(radius, dx, dy, color)
    "setShadow": (4, 0),
    "clearShadow": (0, 0),
    "setStrokeWidth": (1, 0),
    "setFill": (0, 0),
    "setStroke": (0, 0),
    "save": (0, 0),
    "restore": (0, 0),
    "translate": (2, 0),
    "rotate": (1, 0),
    "scale": (2, 0),
    "clipRect": (4, 0),
    "drawText": (3, 0),
    "drawRect": (4, 0),
    "drawCircle": (3, 0),
    "drawLine": (4, 0),
    # drawRoundRect(x, y, w, h, rx, ry)
    "drawRoundRect": (6, 0),
    # drawOval(x, y, w, h)
    "drawOval": (4, 0),
    # drawArc(x, y, w, h, start, sweep)
    "drawArc": (6, 0),
    "pathMove": (2, 0),
    "pathLine": (2, 0),
    # pathQuad(cx, cy, x, y)
    "pathQuad": (4, 0),
    # pathCubic(c1x, c1y, c2x, c2y, x, y)
    "pathCubic": (6, 0),
    "pathClose": (0, 0),
    "drawPath": (0, 0),
    "measureText": (1, 1),
    "end": (0, 0),
}


class ProgramError(ValueError):
    """Raised when an assembled program would be rejected by the app."""


# ---------------------------------------------------------------------------
# Constant handling
# ---------------------------------------------------------------------------


def _format_number(value: int | float) -> str:
    if isinstance(value, bool):  # bool is an int subclass; handled earlier
        raise ProgramError("bool is not a number constant")
    if isinstance(value, int):
        return str(value)
    if not math.isfinite(value):
        raise ProgramError(f"non-finite number constant: {value!r}")
    return repr(value)


def normalize_constant(value: Any) -> dict[str, str]:
    """Return an ``EffectConstant`` dict (``type`` + ``value``)."""
    if isinstance(value, dict):
        unknown = set(value) - {"type", "value"}
        if unknown:
            raise ProgramError(f"unknown constant keys: {sorted(unknown)}")
        ctype = value.get("type")
        if ctype is None:
            ctype = "number"
        if ctype not in ("number", "string", "bool"):
            raise ProgramError(f"bad constant type: {ctype!r}")
        raw = value.get("value", "0")
        if not isinstance(raw, str):
            # Allow {"type": "number", "value": 0.5} for convenience.
            if ctype == "number" and isinstance(raw, (int, float)) and not isinstance(raw, bool):
                raw = _format_number(raw)
            elif ctype == "bool" and isinstance(raw, bool):
                raw = "true" if raw else "false"
            else:
                raise ProgramError(f"constant value must be a string: {raw!r}")
        constant = {"type": ctype, "value": raw}
    elif isinstance(value, bool):
        constant = {"type": "bool", "value": "true" if value else "false"}
    elif isinstance(value, (int, float)):
        constant = {"type": "number", "value": _format_number(value)}
    elif isinstance(value, str):
        constant = {"type": "string", "value": value}
    else:
        raise ProgramError(f"unsupported constant: {value!r}")
    _validate_constant(constant, 0)
    return constant


def _constant_key(constant: dict[str, str]) -> tuple[str, str]:
    return constant.get("type") or "number", constant.get("value", "0")


def _validate_constant(constant: Any, index: int) -> None:
    if not isinstance(constant, dict):
        raise ProgramError(f"constant #{index} is not an object")
    ctype = constant.get("type")
    if ctype is None:
        ctype = "number"
    if ctype not in ("number", "string", "bool"):
        raise ProgramError(f"constant #{index}: bad type {ctype!r}")
    value = constant.get("value", "0")
    if not isinstance(value, str):
        raise ProgramError(f"constant #{index}: value must be a string")
    if ctype == "number":
        try:
            number = float(value)
        except ValueError as exc:
            raise ProgramError(f"constant #{index}: not a number: {value!r}") from exc
        if not math.isfinite(number):
            raise ProgramError(f"constant #{index}: non-finite number {value!r}")
    elif ctype == "string":
        if len(value) > 256:
            raise ProgramError(f"constant #{index}: string longer than 256 chars")
    elif value not in ("true", "false"):
        raise ProgramError(f"constant #{index}: bool must be 'true' or 'false'")


# ---------------------------------------------------------------------------
# Program validation (mirrors normalizeEffectProgram + static stack check)
# ---------------------------------------------------------------------------


def validate_program(program: Any) -> None:
    """Validate a program dict the way ``normalizeEffectProgram`` would.

    Raises :class:`ProgramError` with a human-readable reason.  Also runs a
    static stack-depth check over all control-flow paths so assembler mistakes
    are caught before the app silently pops nulls.
    """
    if not isinstance(program, dict):
        raise ProgramError("program must be an object")

    version = program.get("version", EFFECT_PROGRAM_VERSION)
    if isinstance(version, bool) or version != EFFECT_PROGRAM_VERSION:
        raise ProgramError(f"program version must be {EFFECT_PROGRAM_VERSION}, got {version!r}")

    constants = program.get("constants", [])
    if not isinstance(constants, list):
        raise ProgramError("constants must be an array")
    if len(constants) > MAX_EFFECT_CONSTANTS:
        raise ProgramError(
            f"too many constants: {len(constants)} > {MAX_EFFECT_CONSTANTS}"
        )
    for index, constant in enumerate(constants):
        _validate_constant(constant, index)

    code = program.get("code", [])
    if not isinstance(code, list):
        raise ProgramError("code must be an array")
    if not code:
        raise ProgramError("code must not be empty")
    if len(code) > MAX_EFFECT_CODE:
        raise ProgramError(f"too many instructions: {len(code)} > {MAX_EFFECT_CODE}")

    max_locals = program.get("maxLocals", 16)
    if isinstance(max_locals, bool) or not isinstance(max_locals, int):
        raise ProgramError("maxLocals must be an integer")
    if not 0 <= max_locals <= MAX_EFFECT_LOCALS:
        raise ProgramError(
            f"maxLocals must be 0..{MAX_EFFECT_LOCALS}, got {max_locals}"
        )

    # Same conservative budget as normalizeEffectProgram: 12 bytes/instruction
    # plus value length + 8 per constant.
    estimated_bytes = len(code) * 12
    for constant in constants:
        estimated_bytes += len(constant.get("value", "0")) + 8
    if estimated_bytes > MAX_EFFECT_PROGRAM_ESTIMATED_BYTES:
        raise ProgramError(
            f"estimated program size {estimated_bytes} bytes > "
            f"{MAX_EFFECT_PROGRAM_ESTIMATED_BYTES}"
        )

    for index, instruction in enumerate(code):
        if not isinstance(instruction, dict):
            raise ProgramError(f"instruction #{index} is not an object")
        op = instruction.get("op")
        if not isinstance(op, str) or op not in EFFECT_OPS:
            raise ProgramError(f"instruction #{index}: unknown op {op!r}")
        arg = instruction.get("arg")
        text = instruction.get("text")
        if op in ARG_OPS:
            if isinstance(arg, bool) or not isinstance(arg, int):
                raise ProgramError(f"instruction #{index} ({op}): arg must be an integer")
            if op == "const":
                if not 0 <= arg < len(constants):
                    raise ProgramError(
                        f"instruction #{index} (const): arg {arg} outside 0..{len(constants) - 1}"
                    )
            elif op in ("loadLocal", "storeLocal"):
                if not 0 <= arg < max_locals:
                    raise ProgramError(
                        f"instruction #{index} ({op}): arg {arg} outside 0..{max_locals - 1}"
                    )
            elif not 0 <= arg <= len(code):
                raise ProgramError(
                    f"instruction #{index} ({op}): target {arg} outside 0..{len(code)}"
                )
        elif op in FIELD_OPS:
            allowed = FIELD_OPS[op]
            if not isinstance(text, str) or text not in allowed:
                raise ProgramError(
                    f"instruction #{index} ({op}): field {text!r} not in {sorted(allowed)}"
                )

    _validate_stack(code)


def _validate_stack(code: list[dict[str, Any]]) -> None:
    """Walk every reachable path and check stack depth is consistent."""
    depth: dict[int, int] = {0: 0}
    work: list[int] = [0]
    while work:
        pc = work.pop()
        current = depth[pc]
        if pc == len(code):
            continue
        instruction = code[pc]
        op = instruction["op"]
        pops, pushes = STACK_EFFECT[op]
        if current < pops:
            raise ProgramError(
                f"instruction #{pc} ({op}): stack underflow, depth {current}, needs {pops}"
            )
        after = current - pops + pushes
        if after > MAX_EFFECT_STACK:
            raise ProgramError(
                f"instruction #{pc} ({op}): stack depth {after} > {MAX_EFFECT_STACK}"
            )
        if op == "end":
            continue
        if op == "jump":
            successors = [instruction["arg"]]
        elif op in ("jz", "jnz"):
            successors = [instruction["arg"], pc + 1]
        else:
            successors = [pc + 1]
        for target in successors:
            if not 0 <= target <= len(code):
                raise ProgramError(f"instruction #{pc} ({op}): target {target} out of range")
            if target in depth:
                if depth[target] != after:
                    raise ProgramError(
                        f"stack depth mismatch at #{target}: "
                        f"{depth[target]} vs {after} (from #{pc} {op})"
                    )
            else:
                depth[target] = after
                work.append(target)


# ---------------------------------------------------------------------------
# Builder
# ---------------------------------------------------------------------------


class ProgramBuilder:
    """Fluent builder for an ``EffectProgram`` JSON object."""

    def __init__(
        self,
        *,
        max_locals: int = 8,
        constants: Sequence[Any] = (),
    ) -> None:
        if isinstance(max_locals, bool) or not isinstance(max_locals, int):
            raise ProgramError("max_locals must be an integer")
        if not 0 <= max_locals <= MAX_EFFECT_LOCALS:
            raise ProgramError(f"max_locals must be 0..{MAX_EFFECT_LOCALS}")
        self.max_locals = max_locals
        self.constants: list[dict[str, str]] = []
        self._constant_index: dict[tuple[str, str], int] = {}
        for value in constants:
            self._add_constant(value)
        self.code: list[dict[str, Any]] = []
        self._labels: dict[str, int] = {}
        self._fixups: list[tuple[int, str]] = []

    # -- constants ---------------------------------------------------------
    def _add_constant(self, value: Any) -> int:
        constant = normalize_constant(value)
        key = _constant_key(constant)
        existing = self._constant_index.get(key)
        if existing is not None:
            return existing
        if len(self.constants) >= MAX_EFFECT_CONSTANTS:
            raise ProgramError(f"too many constants (max {MAX_EFFECT_CONSTANTS})")
        index = len(self.constants)
        self.constants.append(constant)
        self._constant_index[key] = index
        return index

    def const(self, value: Any) -> "ProgramBuilder":
        """Intern ``value`` in the constant pool and emit ``const``."""
        return self.emit("const", self._add_constant(value))

    # -- instructions ------------------------------------------------------
    def emit(self, op: str, arg: Any = None, text: str | None = None) -> "ProgramBuilder":
        """Append one instruction.  ``arg`` may be a label for jump ops."""
        if not isinstance(op, str) or op not in EFFECT_OPS:
            raise ProgramError(f"unknown op {op!r} at instruction #{len(self.code)}")
        label_fixup = False
        if op in JUMP_OPS and isinstance(arg, str):
            self._fixups.append((len(self.code), arg))
            arg = None
            label_fixup = True
        if op in ARG_OPS:
            if not label_fixup and (isinstance(arg, bool) or not isinstance(arg, int)):
                raise ProgramError(
                    f"{op} at #{len(self.code)} requires an integer arg or label"
                )
        elif op in FIELD_OPS:
            allowed = FIELD_OPS[op]
            if not isinstance(text, str) or text not in allowed:
                raise ProgramError(
                    f"{op} at #{len(self.code)} requires text in {sorted(allowed)}"
                )
        else:
            if arg is not None or text is not None:
                raise ProgramError(
                    f"{op} at #{len(self.code)} takes no arg/text"
                )
        instruction: dict[str, Any] = {"op": op}
        if arg is not None:
            instruction["arg"] = arg
        if text is not None:
            instruction["text"] = text
        self.code.append(instruction)
        return self

    def label(self, name: str) -> "ProgramBuilder":
        if not isinstance(name, str) or not name:
            raise ProgramError("label name must be a non-empty string")
        if name in self._labels:
            raise ProgramError(f"duplicate label {name!r}")
        self._labels[name] = len(self.code)
        return self

    # -- finish ------------------------------------------------------------
    def finish(self) -> dict[str, Any]:
        for index, name in self._fixups:
            if name not in self._labels:
                raise ProgramError(f"unknown label {name!r} at instruction #{index}")
            self.code[index]["arg"] = self._labels[name]
        program = {
            "version": EFFECT_PROGRAM_VERSION,
            "maxLocals": self.max_locals,
            "constants": [dict(c) for c in self.constants],
            "code": [dict(i) for i in self.code],
        }
        validate_program(program)
        return program


def assemble(
    instructions: Iterable[Sequence[Any]],
    constants: Sequence[Any] = (),
    max_locals: int = 8,
) -> dict[str, Any]:
    """Assemble ``(op, arg, text)`` tuples into a validated program dict.

    ``("label", "name")`` declares a jump target; ``("jz", "name")`` and the
    other jump ops accept a label string in ``arg``.
    """
    builder = ProgramBuilder(max_locals=max_locals, constants=constants)
    for position, item in enumerate(instructions):
        if not isinstance(item, (tuple, list)) or not 1 <= len(item) <= 3:
            raise ProgramError(
                f"instruction #{position}: expected (op, arg, text), got {item!r}"
            )
        padded = list(item) + [None] * (3 - len(item))
        op, arg, text = padded
        if op == "label":
            if not isinstance(arg, str):
                raise ProgramError(f"instruction #{position}: label needs a string name")
            builder.label(arg)
        else:
            builder.emit(op, arg, text)
    return builder.finish()


# ---------------------------------------------------------------------------
# Sample programs
# ---------------------------------------------------------------------------


def build_bounce() -> ProgramBuilder:
    """Per-word bounce: scale about the word center with a sine pulse.

    ``scale = 1 + 0.18 * sin(word.progress * pi)``; alpha fades from 0.35 to 1.
    """
    b = ProgramBuilder(max_locals=8)
    # count = wordCount; i = 0
    b.emit("wordCount")
    b.emit("storeLocal", 1)
    b.const(0)
    b.emit("storeLocal", 0)
    b.label("loop")
    # while (i < count)
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 1)
    b.emit("lt")
    b.emit("jz", "done")
    b.emit("save")
    # ref = word(i)
    b.emit("loadLocal", 0)
    b.emit("word")
    b.emit("storeLocal", 7)
    # cx = word.x + word.width / 2
    b.emit("loadLocal", 7)
    b.emit("wordField", text="x")
    b.emit("loadLocal", 7)
    b.emit("wordField", text="width")
    b.const(2)
    b.emit("div")
    b.emit("add")
    b.emit("storeLocal", 2)
    # cy = baseline - textSize * 0.35  (approximate visual center)
    b.emit("globalField", text="baseline")
    b.emit("globalField", text="textSize")
    b.const(0.35)
    b.emit("mul")
    b.emit("sub")
    b.emit("storeLocal", 3)
    # p = word.progress
    b.emit("loadLocal", 7)
    b.emit("wordField", text="progress")
    b.emit("storeLocal", 4)
    # scale = 1 + 0.18 * sin(p * 3.14159)
    b.const(1)
    b.const(0.18)
    b.emit("loadLocal", 4)
    b.const(3.14159)
    b.emit("mul")
    b.emit("sin")
    b.emit("mul")
    b.emit("add")
    b.emit("storeLocal", 5)
    # translate(cx, cy); scale(s, s); translate(-cx, -cy)
    b.emit("loadLocal", 2)
    b.emit("loadLocal", 3)
    b.emit("translate")
    b.emit("loadLocal", 5)
    b.emit("loadLocal", 5)
    b.emit("scale")
    b.emit("loadLocal", 2)
    b.emit("neg")
    b.emit("loadLocal", 3)
    b.emit("neg")
    b.emit("translate")
    # setColor(sung)
    b.emit("colorField", text="sung")
    b.emit("setColor")
    # setAlpha(0.35 + 0.65 * p)
    b.const(0.35)
    b.const(0.65)
    b.emit("loadLocal", 4)
    b.emit("mul")
    b.emit("add")
    b.emit("setAlpha")
    # drawText(word.text, word.x, baseline)
    b.emit("loadLocal", 7)
    b.emit("wordField", text="text")
    b.emit("loadLocal", 7)
    b.emit("wordField", text="x")
    b.emit("globalField", text="baseline")
    b.emit("drawText")
    b.emit("restore")
    # i += 1
    b.emit("loadLocal", 0)
    b.const(1)
    b.emit("add")
    b.emit("storeLocal", 0)
    b.emit("jump", "loop")
    b.label("done")
    b.emit("end")
    return b


def build_spin() -> ProgramBuilder:
    """Per-word spin: rotate about the word center by ``progress * 360``."""
    b = ProgramBuilder(max_locals=5)
    # count = wordCount; i = 0
    b.emit("wordCount")
    b.emit("storeLocal", 1)
    b.const(0)
    b.emit("storeLocal", 0)
    b.label("loop")
    # while (i < count)
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 1)
    b.emit("lt")
    b.emit("jz", "done")
    b.emit("save")
    # ref = word(i)
    b.emit("loadLocal", 0)
    b.emit("word")
    b.emit("storeLocal", 2)
    # cx = word.x + word.width / 2
    b.emit("loadLocal", 2)
    b.emit("wordField", text="x")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="width")
    b.const(2)
    b.emit("div")
    b.emit("add")
    b.emit("storeLocal", 3)
    # cy = baseline - textSize * 0.35
    b.emit("globalField", text="baseline")
    b.emit("globalField", text="textSize")
    b.const(0.35)
    b.emit("mul")
    b.emit("sub")
    b.emit("storeLocal", 4)
    # translate(cx, cy); rotate(progress * 360); translate(-cx, -cy)
    b.emit("loadLocal", 3)
    b.emit("loadLocal", 4)
    b.emit("translate")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="progress")
    b.const(360)
    b.emit("mul")
    b.emit("rotate")
    b.emit("loadLocal", 3)
    b.emit("neg")
    b.emit("loadLocal", 4)
    b.emit("neg")
    b.emit("translate")
    # setColor(sung)
    b.emit("colorField", text="sung")
    b.emit("setColor")
    # setAlpha(progress) — fade in as the word is sung
    b.emit("loadLocal", 2)
    b.emit("wordField", text="progress")
    b.emit("setAlpha")
    # drawText(word.text, word.x, baseline)
    b.emit("loadLocal", 2)
    b.emit("wordField", text="text")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="x")
    b.emit("globalField", text="baseline")
    b.emit("drawText")
    b.emit("restore")
    # i += 1
    b.emit("loadLocal", 0)
    b.const(1)
    b.emit("add")
    b.emit("storeLocal", 0)
    b.emit("jump", "loop")
    b.label("done")
    b.emit("end")
    return b


def build_line_wave() -> ProgramBuilder:
    """Whole-line wave: a rounded underline per visual line plus a per-word wave.

    The underline loop walks ``lineCount`` / ``line`` / ``lineAtField`` and draws
    a thin rounded rect just below each visual line's baseline.  The word loop
    uses ``word.index`` and ``word.progress`` for the wave phase and draws every
    word at its own ``word.baseline`` so wrapped lines stay aligned.
    """
    b = ProgramBuilder(max_locals=8)

    # -- line-wide underline ------------------------------------------------
    b.emit("lineCount")
    b.emit("storeLocal", 1)              # n = lineCount
    b.const(0)
    b.emit("storeLocal", 0)              # i = 0
    b.label("lines")
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 1)
    b.emit("lt")
    b.emit("jz", "words")
    b.emit("loadLocal", 0)
    b.emit("line")
    b.emit("storeLocal", 2)              # ref = line(i)
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="x")
    b.emit("storeLocal", 3)              # x = line.x
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="width")
    b.emit("storeLocal", 4)              # w = line.width
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="height")
    b.const(0.08)
    b.emit("mul")
    b.emit("storeLocal", 5)              # h = line.height * 0.08
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="baseline")
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="height")
    b.const(0.14)
    b.emit("mul")
    b.emit("add")
    b.emit("storeLocal", 6)              # y = line.baseline + line.height * 0.14
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="progress")
    b.emit("storeLocal", 7)              # lp = line.progress
    b.emit("colorField", text="sung")
    b.emit("setColor")
    b.const(0.12)
    b.const(0.28)
    b.emit("loadLocal", 7)
    b.emit("mul")
    b.emit("add")
    b.emit("setAlpha")                   # 0.12 + 0.28 * lp
    b.emit("loadLocal", 3)
    b.emit("loadLocal", 6)
    b.emit("loadLocal", 4)
    b.emit("loadLocal", 5)
    b.emit("loadLocal", 5)
    b.const(0.5)
    b.emit("mul")
    b.emit("loadLocal", 5)
    b.const(0.5)
    b.emit("mul")
    b.emit("drawRoundRect")              # (x, y, w, h, h/2, h/2)
    b.emit("loadLocal", 0)
    b.const(1)
    b.emit("add")
    b.emit("storeLocal", 0)
    b.emit("jump", "lines")

    # -- per-word wave ------------------------------------------------------
    b.label("words")
    b.emit("wordCount")
    b.emit("storeLocal", 1)              # n = wordCount
    b.const(0)
    b.emit("storeLocal", 0)              # i = 0
    b.label("word_loop")
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 1)
    b.emit("lt")
    b.emit("jz", "done")
    b.emit("save")
    b.emit("loadLocal", 0)
    b.emit("word")
    b.emit("storeLocal", 2)              # ref = word(i)
    # cx = word.x + word.width / 2
    b.emit("loadLocal", 2)
    b.emit("wordField", text="x")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="width")
    b.const(2)
    b.emit("div")
    b.emit("add")
    b.emit("storeLocal", 3)
    # p = word.progress
    b.emit("loadLocal", 2)
    b.emit("wordField", text="progress")
    b.emit("storeLocal", 5)
    # phase = word.index * 0.9 + p * 3.14159
    b.emit("loadLocal", 2)
    b.emit("wordField", text="index")
    b.const(0.9)
    b.emit("mul")
    b.emit("loadLocal", 5)
    b.const(3.14159)
    b.emit("mul")
    b.emit("add")
    b.emit("storeLocal", 6)
    # lift = sin(phase) * textSize * 0.15
    b.emit("loadLocal", 6)
    b.emit("sin")
    b.emit("globalField", text="textSize")
    b.emit("mul")
    b.const(0.15)
    b.emit("mul")
    b.emit("storeLocal", 7)
    # cy = word.baseline - textSize * 0.35 + lift
    b.emit("loadLocal", 2)
    b.emit("wordField", text="baseline")
    b.emit("globalField", text="textSize")
    b.const(0.35)
    b.emit("mul")
    b.emit("sub")
    b.emit("loadLocal", 7)
    b.emit("add")
    b.emit("storeLocal", 4)
    # s = 1 + 0.12 * sin(phase)
    b.const(1)
    b.const(0.12)
    b.emit("loadLocal", 6)
    b.emit("sin")
    b.emit("mul")
    b.emit("add")
    b.emit("storeLocal", 6)
    # translate(cx, cy); scale(s, s); translate(-cx, -cy)
    b.emit("loadLocal", 3)
    b.emit("loadLocal", 4)
    b.emit("translate")
    b.emit("loadLocal", 6)
    b.emit("loadLocal", 6)
    b.emit("scale")
    b.emit("loadLocal", 3)
    b.emit("neg")
    b.emit("loadLocal", 4)
    b.emit("neg")
    b.emit("translate")
    b.emit("colorField", text="sung")
    b.emit("setColor")
    # setAlpha(0.35 + 0.65 * p)
    b.const(0.35)
    b.const(0.65)
    b.emit("loadLocal", 5)
    b.emit("mul")
    b.emit("add")
    b.emit("setAlpha")
    # drawText(word.text, word.x, word.baseline)
    b.emit("loadLocal", 2)
    b.emit("wordField", text="text")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="x")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="baseline")
    b.emit("drawText")
    b.emit("restore")
    b.emit("loadLocal", 0)
    b.const(1)
    b.emit("add")
    b.emit("storeLocal", 0)
    b.emit("jump", "word_loop")
    b.label("done")
    b.emit("end")
    return b


def build_line_gradient() -> ProgramBuilder:
    """Whole-block gradient text plus a sweeping rounded highlight.

    ``setGradient`` spans ``blockWidth`` x ``blockHeight`` from ``sung`` to
    ``accent``; words are drawn at their own ``word.baseline``.  After the text
    the gradient is cleared and a rounded band sweeps across the first visual
    line using ``lineProgress`` and a ``time``-driven alpha pulse.
    """
    b = ProgramBuilder(max_locals=8)
    b.emit("save")

    # Gradient across the whole block: (0, 0) -> (blockWidth, blockHeight).
    b.const(0)
    b.const(0)
    b.emit("globalField", text="blockWidth")
    b.emit("globalField", text="blockHeight")
    b.emit("colorField", text="sung")
    b.emit("colorField", text="accent")
    b.emit("setGradient")

    # -- words --------------------------------------------------------------
    b.emit("wordCount")
    b.emit("storeLocal", 1)
    b.const(0)
    b.emit("storeLocal", 0)
    b.label("words")
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 1)
    b.emit("lt")
    b.emit("jz", "sweep")
    b.emit("loadLocal", 0)
    b.emit("word")
    b.emit("storeLocal", 2)              # ref = word(i)
    b.emit("loadLocal", 2)
    b.emit("wordField", text="progress")
    b.emit("storeLocal", 3)              # p = word.progress
    # setAlpha(0.30 + 0.70 * p)
    b.const(0.3)
    b.const(0.7)
    b.emit("loadLocal", 3)
    b.emit("mul")
    b.emit("add")
    b.emit("setAlpha")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="text")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="x")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="baseline")
    b.emit("drawText")
    b.emit("loadLocal", 0)
    b.const(1)
    b.emit("add")
    b.emit("storeLocal", 0)
    b.emit("jump", "words")

    # -- sweeping rounded highlight ----------------------------------------
    b.label("sweep")
    b.emit("clearGradient")
    b.const(0)
    b.emit("line")
    b.emit("storeLocal", 2)              # ref = line(0)
    # bandW = blockWidth * 0.16
    b.emit("globalField", text="blockWidth")
    b.const(0.16)
    b.emit("mul")
    b.emit("storeLocal", 3)
    # h = line.height
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="height")
    b.emit("storeLocal", 4)
    # x = (blockWidth + 2 * bandW) * lineProgress - bandW
    b.emit("globalField", text="blockWidth")
    b.emit("loadLocal", 3)
    b.const(2)
    b.emit("mul")
    b.emit("add")
    b.emit("lineField", text="progress")
    b.emit("mul")
    b.emit("loadLocal", 3)
    b.emit("sub")
    b.emit("storeLocal", 5)
    # y = line.baseline - line.height * 0.75
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="baseline")
    b.emit("loadLocal", 4)
    b.const(0.75)
    b.emit("mul")
    b.emit("sub")
    b.emit("storeLocal", 6)
    # setAlpha(0.08 + 0.10 * (0.5 + 0.5 * sin(time / 260)))
    b.const(0.08)
    b.const(0.1)
    b.const(0.5)
    b.const(0.5)
    b.emit("globalField", text="time")
    b.const(260)
    b.emit("div")
    b.emit("sin")
    b.emit("mul")
    b.emit("add")
    b.emit("mul")
    b.emit("add")
    b.emit("setAlpha")
    b.emit("colorField", text="glow")
    b.emit("setColor")
    b.emit("loadLocal", 5)
    b.emit("loadLocal", 6)
    b.emit("loadLocal", 3)
    b.emit("loadLocal", 4)
    b.emit("loadLocal", 4)
    b.const(0.5)
    b.emit("mul")
    b.emit("loadLocal", 4)
    b.const(0.5)
    b.emit("mul")
    b.emit("drawRoundRect")
    b.emit("restore")
    b.emit("end")
    return b


def build_particles() -> ProgramBuilder:
    """Words plus 28 drifting particles around the visual lines.

    Particles are placed with ``line(i % lineCount)`` and ``lineAtField``
    geometry; ``wall`` drives the drift so the sparkles move independently of
    playback.  Each particle costs one ``drawCircle``, so the whole frame stays
    far below the 4096 draw-op budget.
    """
    b = ProgramBuilder(max_locals=8)

    # -- words --------------------------------------------------------------
    b.emit("wordCount")
    b.emit("storeLocal", 1)
    b.const(0)
    b.emit("storeLocal", 0)
    b.label("words")
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 1)
    b.emit("lt")
    b.emit("jz", "particles")
    b.emit("loadLocal", 0)
    b.emit("word")
    b.emit("storeLocal", 2)              # ref = word(i)
    b.emit("colorField", text="sung")
    b.emit("setColor")
    # setAlpha(0.40 + 0.60 * word.progress)
    b.const(0.4)
    b.const(0.6)
    b.emit("loadLocal", 2)
    b.emit("wordField", text="progress")
    b.emit("mul")
    b.emit("add")
    b.emit("setAlpha")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="text")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="x")
    b.emit("loadLocal", 2)
    b.emit("wordField", text="baseline")
    b.emit("drawText")
    b.emit("loadLocal", 0)
    b.const(1)
    b.emit("add")
    b.emit("storeLocal", 0)
    b.emit("jump", "words")

    # -- 28 particles -------------------------------------------------------
    b.label("particles")
    b.const(28)
    b.emit("storeLocal", 1)              # count = 28
    b.emit("lineCount")
    b.const(1)
    b.emit("max")
    b.emit("storeLocal", 7)              # lineCount, floored at 1
    b.const(0)
    b.emit("storeLocal", 0)              # i = 0
    b.label("particle_loop")
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 1)
    b.emit("lt")
    b.emit("jz", "done")
    b.emit("loadLocal", 0)
    b.emit("loadLocal", 7)
    b.emit("mod")
    b.emit("line")
    b.emit("storeLocal", 2)              # ref = line(i % lineCount)
    # x = line.x + line.width * (0.5 + 0.5 * sin(wall / 1800 + i * 2.399))
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="x")
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="width")
    b.const(0.5)
    b.const(0.5)
    b.emit("globalField", text="wall")
    b.const(1800)
    b.emit("div")
    b.emit("loadLocal", 0)
    b.const(2.399)
    b.emit("mul")
    b.emit("add")
    b.emit("sin")
    b.emit("mul")
    b.emit("add")
    b.emit("mul")
    b.emit("add")
    b.emit("storeLocal", 3)
    # y = line.baseline - line.height * (0.2 + 0.6 * (0.5 + 0.5 * cos(wall / 2100 + i * 1.7)))
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="baseline")
    b.emit("loadLocal", 2)
    b.emit("lineAtField", text="height")
    b.const(0.2)
    b.const(0.6)
    b.const(0.5)
    b.const(0.5)
    b.emit("globalField", text="wall")
    b.const(2100)
    b.emit("div")
    b.emit("loadLocal", 0)
    b.const(1.7)
    b.emit("mul")
    b.emit("add")
    b.emit("cos")
    b.emit("mul")
    b.emit("add")
    b.emit("mul")
    b.emit("add")
    b.emit("mul")
    b.emit("sub")
    b.emit("storeLocal", 4)
    # r = 1.5 + 1.5 * (0.5 + 0.5 * sin(wall / 900 + i * 0.9))
    b.const(1.5)
    b.const(1.5)
    b.const(0.5)
    b.const(0.5)
    b.emit("globalField", text="wall")
    b.const(900)
    b.emit("div")
    b.emit("loadLocal", 0)
    b.const(0.9)
    b.emit("mul")
    b.emit("add")
    b.emit("sin")
    b.emit("mul")
    b.emit("add")
    b.emit("mul")
    b.emit("add")
    b.emit("storeLocal", 5)
    # setAlpha(0.12 + 0.30 * (0.5 + 0.5 * sin(wall / 700 + i * 1.3)))
    b.const(0.12)
    b.const(0.3)
    b.const(0.5)
    b.const(0.5)
    b.emit("globalField", text="wall")
    b.const(700)
    b.emit("div")
    b.emit("loadLocal", 0)
    b.const(1.3)
    b.emit("mul")
    b.emit("add")
    b.emit("sin")
    b.emit("mul")
    b.emit("add")
    b.emit("mul")
    b.emit("add")
    b.emit("setAlpha")
    b.emit("colorField", text="accent")
    b.emit("setColor")
    b.emit("loadLocal", 3)
    b.emit("loadLocal", 4)
    b.emit("loadLocal", 5)
    b.emit("drawCircle")
    b.emit("loadLocal", 0)
    b.const(1)
    b.emit("add")
    b.emit("storeLocal", 0)
    b.emit("jump", "particle_loop")
    b.label("done")
    b.emit("end")
    return b


def build_pack() -> dict[str, Any]:
    """Build the sample pack (per-word and whole-line programs)."""
    pack = {
        "format": PACK_FORMAT,
        "version": PACK_VERSION,
        "name": "Program Effect Pack",
        "author": "HyperGlow",
        "effects": [
            {
                "id": "program_bounce",
                "name": "Program Bounce",
                "program": build_bounce().finish(),
            },
            {
                "id": "program_spin",
                "name": "Program Spin",
                "program": build_spin().finish(),
            },
            {
                "id": "program_line_wave",
                "name": "Program Line Wave",
                "program": build_line_wave().finish(),
            },
            {
                "id": "program_line_gradient",
                "name": "Program Line Gradient",
                "program": build_line_gradient().finish(),
            },
            {
                "id": "program_particles",
                "name": "Program Particles",
                "program": build_particles().finish(),
            },
        ],
    }
    validate_pack(pack)
    return pack


# ---------------------------------------------------------------------------
# Pack validation
# ---------------------------------------------------------------------------


def validate_pack(pack: Any) -> None:
    """Validate the pack envelope and every effect program."""
    if not isinstance(pack, dict):
        raise ProgramError("pack must be an object")
    if pack.get("format") != PACK_FORMAT:
        raise ProgramError(f"format must be {PACK_FORMAT!r}")
    if isinstance(pack.get("version"), bool) or pack.get("version") != PACK_VERSION:
        raise ProgramError(f"version must be {PACK_VERSION}")
    effects = pack.get("effects")
    if not isinstance(effects, list) or not 1 <= len(effects) <= MAX_EFFECTS:
        raise ProgramError(f"effects must be an array of 1..{MAX_EFFECTS} items")
    seen: set[str] = set()
    for index, effect in enumerate(effects):
        if not isinstance(effect, dict):
            raise ProgramError(f"effect #{index} is not an object")
        effect_id = effect.get("id")
        if not isinstance(effect_id, str) or not EFFECT_ID_RE.match(effect_id):
            raise ProgramError(f"effect #{index}: bad id {effect_id!r}")
        if effect_id == "none":
            raise ProgramError(f"effect #{index}: id 'none' is reserved")
        if effect_id in seen:
            raise ProgramError(f"effect #{index}: duplicate id {effect_id!r}")
        seen.add(effect_id)
        name = effect.get("name", effect_id)
        if not isinstance(name, str) or len(name) > MAX_NAME_CHARS:
            raise ProgramError(f"effect #{index}: name must be a string <= {MAX_NAME_CHARS} chars")
        if "program" not in effect:
            raise ProgramError(f"effect #{index}: missing program")
        validate_program(effect["program"])


# ---------------------------------------------------------------------------
# Self-check / CLI
# ---------------------------------------------------------------------------


def _estimated_bytes(program: dict[str, Any]) -> int:
    """Same conservative budget as ``normalizeEffectProgram``."""
    total = len(program["code"]) * 12
    for constant in program["constants"]:
        total += len(constant.get("value", "0")) + 8
    return total


def _describe(name: str, program: dict[str, Any]) -> str:
    static_draw_ops = sum(
        1 for i in program["code"] if i["op"] in EFFECT_DRAW_OPS
    )
    return (
        f"{name}: {len(program['code'])} instructions, "
        f"{len(program['constants'])} constants, "
        f"maxLocals={program['maxLocals']}, "
        f"~{_estimated_bytes(program)} bytes, "
        f"{static_draw_ops} draw-op instructions"
    )


def self_check() -> dict[str, Any]:
    """Build all samples, validate them, and return the pack."""
    bounce = build_bounce().finish()
    spin = build_spin().finish()
    line_wave = build_line_wave().finish()
    line_gradient = build_line_gradient().finish()
    particles = build_particles().finish()
    programs = {
        "program_bounce": bounce,
        "program_spin": spin,
        "program_line_wave": line_wave,
        "program_line_gradient": line_gradient,
        "program_particles": particles,
    }
    for program in programs.values():
        validate_program(program)
        assert _estimated_bytes(program) <= MAX_EFFECT_PROGRAM_ESTIMATED_BYTES
        assert (
            sum(1 for i in program["code"] if i["op"] in EFFECT_DRAW_OPS)
            <= MAX_EFFECT_DRAW_OPS_PER_FRAME
        )

    # Structural expectations that keep the samples honest.
    assert bounce["code"][-1] == {"op": "end"}, "bounce must end with end"
    assert spin["code"][-1] == {"op": "end"}, "spin must end with end"
    bounce_jz = next(i for i in bounce["code"] if i["op"] == "jz")
    assert bounce_jz["arg"] == len(bounce["code"]) - 1, "bounce loop exit must target end"
    bounce_jump = next(i for i in bounce["code"] if i["op"] == "jump")
    assert bounce_jump["arg"] == 4, "bounce loop head must be instruction #4"
    spin_jz = next(i for i in spin["code"] if i["op"] == "jz")
    assert spin_jz["arg"] == len(spin["code"]) - 1, "spin loop exit must target end"
    spin_jump = next(i for i in spin["code"] if i["op"] == "jump")
    assert spin_jump["arg"] == 4, "spin loop head must be instruction #4"
    for name, program in programs.items():
        assert sum(1 for i in program["code"] if i["op"] == "save") == sum(
            1 for i in program["code"] if i["op"] == "restore"
        ), f"{name} save/restore must balance"

    def ops(program: dict[str, Any]) -> set[str]:
        return {i["op"] for i in program["code"]}

    def fields(program: dict[str, Any], op: str) -> set[str]:
        return {i.get("text") for i in program["code"] if i["op"] == op}

    # Whole-line samples must actually use the whole-block context.
    assert {"lineCount", "line", "lineAtField", "drawRoundRect"} <= ops(line_wave)
    assert "baseline" in fields(line_wave, "wordField")
    assert {"setGradient", "clearGradient", "drawRoundRect"} <= ops(line_gradient)
    assert {"blockWidth", "blockHeight"} <= fields(line_gradient, "globalField")
    assert "baseline" in fields(line_gradient, "wordField")
    assert {"lineCount", "line", "lineAtField", "drawCircle"} <= ops(particles)
    assert "wall" in fields(particles, "globalField")

    pack = build_pack()
    text = json.dumps(pack, ensure_ascii=False, indent=2) + "\n"
    json.loads(text)  # JSON round-trip
    size = len(text.encode("utf-8"))
    assert size <= MAX_PACK_BYTES, f"pack is {size} bytes > {MAX_PACK_BYTES}"
    print("self-check OK")
    for name, program in programs.items():
        print("  " + _describe(name, program))
    print(f"  pack: {size} bytes (limit {MAX_PACK_BYTES})")
    return pack


def main(argv: Sequence[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--out",
        type=Path,
        default=root / "samples" / "word-effect-program-pack.json",
        help="output pack path (default: samples/word-effect-program-pack.json)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="run the self-check and validate; do not write a file",
    )
    parser.add_argument(
        "--stdout",
        action="store_true",
        help="print the generated JSON instead of writing a file",
    )
    args = parser.parse_args(argv)

    pack = self_check()
    text = json.dumps(pack, ensure_ascii=False, indent=2) + "\n"
    if args.stdout:
        print(text)
        return 0
    if args.check:
        print("validated; no file written")
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {args.out}")
    print(f"validated JSON: {json.loads(text)['format']} v{json.loads(text)['version']}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ProgramError as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
