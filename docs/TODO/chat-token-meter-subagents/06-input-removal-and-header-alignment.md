# 移除底部子智能体入口与工具标题对齐

## 本次调整

按最新需求移除 Agent 与 Classic 输入布局的子智能体入口，删除专用详情组件及其四个中英文字符串。对话流里的子任务状态列表保留，iOS 风格状态圈与全部任务结束后释放对话进度位置的逻辑保持不变。

状态圈左缘从工具正文起点再向右移动 24dp，对齐展开箭头右侧的数量文字。工具标题和进度列表共用 `ExpandableHeaderTitleStart`，其值由 20dp 箭头宽度与 4dp 间距组成，避免两处独立常量漂移。Cursor 与 Bubble 仍使用各自正文起点。

## 验证

- 静态检查：确认两种输入布局无入口、详情组件无残留引用、中英文资源 XML 可解析、差异无空白错误
- 云端验证：本次提交后由 Android Build 与 Android Tests 推送触发，结果以对应提交的 Actions 为准
- 真机待验收：Cursor/Bubble 的状态圈对齐、不同头像和正文内边距设置、浅色/深色主题，以及后续工具流继续显示

前一提交 `56c7494` 的 Android Build 已成功；完整 Debug JVM 测试为 1411 个用例，5 个失败、6 个跳过。失败用例属于 `GeminiThinkingConfigTest`、`OpenAiToolCallHistoryTest`、`StructuredToolCallBridgeHistoryTest`、`JsToolPkgRegistrationTest`、`ReleasedProviderModelKeyDecoderTest`，尚未定位根因，不能将完整测试标为通过。本次不扩大 UI 调整范围修改这些测试或排除失败用例。

原文件保存在仓库外 `operit-feature-work/artifacts/56c7494-input-removal/`，Git 历史也保留此前入口实现。

[DONE] 源码调整；当前提交的云端与真机验收待完成。
