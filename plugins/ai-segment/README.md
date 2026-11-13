# HyperGlow AI 切词插件(ai-segment)

把**超长**的歌词行送给任意 OpenAI 兼容接口,按语义切分成均衡的卡拉OK子行。
播放时对所有歌生效,结果按曲目缓存。

## 安装

1. 构建:`./gradlew :plugins:ai-segment:pluginZip`
   → `build/distributions/hyperglow-ai-segment-plugin.zip`
2. HyperGlow → 插件管理 → 导入 zip
3. 打开插件设置:填接口地址 / 模型 / API 密钥,勾选启用
4. 播放任意歌曲:超过「每行最大字数」的行自动切分

## 行为

- 阶段:`LYRIC_REPLACEMENT`,`REPLACE` 模式整表替换(行数可变 = 拆/并的唯一通道)
- 只切超过「每行最大字数」的行;短行原样保留
- 子行时间窗按显示字符数占比切分源行窗口;词按字符区间归入子行并钳制时间戳
  (源行没有逐字时间时按 CJK 逐字 / 西文按词合成词块,卡拉OK仍有逐字时间)
- 校验:AI 输出的子行按序拼接必须等于源行(忽略空白),不一致整行回落不拆
- 缓存:行级(起始时间 + 行文本 + 配置哈希),30 天 TTL,只在出现**新长行**时请求 AI
- 失败:10 分钟冷却,不写缓存

## 信任边界

与 ai-translation 相同:纯数据往返,不加载代码、不触碰渲染;密钥只发往用户配置的接口,
不进配置备份(manifest `backup: false`)。
