# 02 上下文诊断

## 原状与目标

原页头只展示会话 Token 用量。新增入口解释最近一次主会话请求的文本分类、计数来源、模型窗口及压缩阈值，并展示最近一次成功摘要的触发原因和源快照覆盖范围。既有用量统计保持独立，不把账单与文本估算混为一谈。

## 实现与接线

`ContextDiagnosticsStore` 按 chatId 保存进程内最新请求和压缩事件；`ContextDiagnosticsDialog` 从页头打开，先按类型聚合再展示，避免为长会话渲染逐消息列表。

`EnhancedAIService` 在首轮和工具续轮发送前记录最终请求历史与工具定义。子代理和 delegatedInstance 不覆盖父会话诊断。窗口及阈值随当前阶段模型配置变化；父请求关闭总结时不会被阶段路由重新启用。

`SystemPromptConfig` 用 `assistant_role` 和 `tool_definitions` 标签标记角色与工具定义。`ContextDiagnosticsBuilder` 按 PromptTurn 类型和标签归因；未标记的自定义提示归为系统提示。准备后的 turn 没有持久化消息标识，因此不伪造来源索引。

成功持久化摘要后才记录压缩事件，覆盖以下路径：

- `MessageCoordinationDelegate` 手动总结、发送前异步总结及工具续轮 Token 阈值总结
- `ChatViewModel.performInsertSummary` 在指定消息锚点插入总结，显式绑定原 chatId

覆盖选择与 `AIMessageManager.summarizeMemory` 一致：上一条 summary 及之后的 user/ai；没有旧 summary 时使用传入快照内全部 user/ai。锚点插入传入已选取的历史子集，索引对应该输入子集，不是整个数据库的序号。

删除或清空会话时，`ChatViewModel` 同步清理该会话诊断。

## 计数与隐私

当前接线的请求总量及文本分类标记为 `ESTIMATE`，不能作为服务端计费或精确分词结果。数据模型支持 `LOCAL_TOKENIZER` 和 `SERVER`，但本批未接入服务端实际 usage 回填。协议封装、工具调用参数、媒体载荷和提供方分词可能造成分类合计与请求估算不一致。

压缩前后数值分别是被替换源文本和新摘要的估算，不是两次模型请求总量。未知值显示“未记录”，与真实零值区别显示。

Store 只保留类别、数量、配置上限、原因、时间和索引引用，不保存原始提示词或工具结果。请求与压缩各保留最新一次，进程重启后为空。索引为记录时输入快照内的 0-based 位置，毫秒时间戳不是唯一消息 ID；UI 不用它们回查或修改消息。

中文资源位于默认 `values/strings.xml`，英文位于 `values-en/strings.xml`，无需另建 `values-zh`。

## 静态审查与待验证

已提供 `ContextDiagnosticsStoreTest`、`ContextDiagnosticsBuilderTest` 源码，覆盖隔离、集合复制、分类聚合、旧摘要边界、无旧摘要的选中子集、重复时间戳、空覆盖和禁用阈值。

已静态核对资源键、格式化占位符、调用方参数与压缩选择。未执行编译或测试。设备上仍需验证各摘要入口只在插入成功后记录、切会话不串写、删除清空后的 UI，以及大量历史下的弹窗响应。

[DONE]
