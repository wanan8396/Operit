---
Repository: https://github.com/wanan99123/Operit
Branch: feat/plan-steps-and-subagents
Status: Implementation complete; cloud verification pending
---

# 子任务静默重试与连续执行

## 范围

只修复同一父调用内的子任务失败或超时后的续接、累计工具计数及重复重试输出。计划持久化、上下文诊断和权限审批的在制改动不包含在此提交中。

原实现每次创建新的子服务时传入空历史，并清空子任务工具活动；导致任务从头执行，UI 工具数归零。每次失败还会向父流再次输出重试 JSON。

## 新行为

- 子任务 ID、原始目标与已提交历史在自动重试之间保持不变
- 父流仅输出一次开始状态和最终结果，重试不输出中间失败或重试 JSON
- 工具调用总数累计，工具状态变化不重复计数，最近 64 条活动之外的迟到事件也不增加计数
- 每个工具批次先记录历史和调用列表，提交前保留各工具已完成结果，提交后清除待提交批次
- 中断时未启动的操作标明未执行，已启动但未返回结果的操作标明结果未知，要求模型先核实状态再继续
- 每次尝试的工具预算和超时仍独立，任务累计计数不随预算重建归零
- 父调用取消仍终止子任务，不自动重试，也不创建后台孤儿任务

续接状态仅存在本次父调用的内存中；不承诺进程死亡后的任务恢复，也不能保证外部操作 exactly-once。对于结果未知的写入、上传或提交，只记录事实并要求验证，不自动重放。

## 验证

新增 SubagentContinuationTest，覆盖系统提示重新生成、历史保留、中断批次、包参数注入后匹配、同名工具定位、提交后去重和拒绝结果保留。

更新 SubagentProgressStoreTest，覆盖重试累计、同一调用的状态事件去重、64 条窗口之外迟到结果的去重。现有 SubagentRetryRunnerTest 继续覆盖超时续试、成功兄弟不重启、父取消中止和取消重试等待。

GitHub Android Tests 首先运行这三个目标类，然后执行全量 JVM 单元测试；Android Build 独立构建 APK。云端结果以提交 SHA 对应的 Actions 为准。

本次改动之前的 4e275d3：Android Build 成功，Android Tests 有五个既有失败，分别涉及 GeminiThinkingConfigTest、OpenAiToolCallHistoryTest、StructuredToolCallBridgeHistoryTest、JsToolPkgRegistrationTest、ReleasedProviderModelKeyDecoderTest。本次不扩大范围去修改这些模块。

设备回归：启动两个互不依赖子任务，在其中一个完成工具调用后中断其模型请求。应保留相同子任务 ID 与工具计数，仅该子任务续试，另一任务不重启；对话不追加重试提示。父级停止应立即停止两个子任务。
