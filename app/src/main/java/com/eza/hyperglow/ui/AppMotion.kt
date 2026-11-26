package com.eza.hyperglow.ui

import top.yukonga.miuix.kmp.layout.DialogMotion

/**
 * 统一对话框动效:采用 Miuix 0.9.5 新增的 HyperOS 启动器玻璃对话框曲线——
 * 底部弹出用更软的回落弹簧,居中弹窗从 0.75 缩放淡入,遮罩 100ms 线性收放。
 * 所有 WindowDialog 共用一份,避免各页面动效不一致。
 */
internal val appDialogMotion: DialogMotion = DialogMotion.Glass
