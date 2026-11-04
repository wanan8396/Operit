---
fork: https://github.com/wanan99123/Operit
base: dbf71916fae9750cfdc9f9a774f5a0fee56633fb
branch: feat/chat-token-meter-subagents
---
# 聊天底部 Token 计量与子智能体

## 基线

Android 1.12.2 已有 Token 用量数据库、供应商 usage 归一化、聊天累计计数和顶部上下文提示。输入区底部没有常驻计量。`examples/subagent` 是未内置的实验包，使用全局 EnhancedAIService，工具次数仅为提示词建议。

## 目标

- 在 Android 主聊天的 Agent、Classic 两种输入布局底部显示上下文占用、本次输入和输出、已确认缓存命中率，点击查看当前聊天累计计数
- 区分供应商确认、流式估计与未知值；不把缺失缓存报告显示为零命中
- 内置 `run_subagent` 委托工具。独立模型服务和上下文，使用功能模型配置页面的子智能体配置与模型，继承父请求工作区、角色卡和工具权限
- 在模型工具调用边界强制次数上限，不支持子任务再次委托；设置单任务超时，不限制子智能体数量和并发，父任务取消时清理子任务
- 不替换正在运行的 Operit，不清除数据；修改提交到功能分支

## 作用域

计量数据经过 `EnhancedAIService` → `TokenStatisticsDelegate` → `ChatViewModel` → `ChatTokenMeter`。新增数据仅是 UI 运行态，已有聊天累计数据和数据库版本不变。

子智能体在每次委托开始时读取 `FunctionType.SUBAGENT` 的 `configId` 与 `modelIndex`，不依赖父会话的模型身份。既有功能配置归一化会为新增类型提供默认配置映射，原有枚举顺序保持不变。

执行仍使用 `FunctionType.CHAT` 与显式模型覆盖参数，因为完整聊天流程的覆盖仅对 CHAT 生效。上下文长度与总结阈值读取同一子智能体配置，输出 Token 仍由该独立服务的实际执行累计，不读取父请求或全局功能服务统计。

子任务通过 `ToolRegistration` 注册并添加中英文结构化提示。使用现有权限检查与 provider 统计，不自行创建绕过权限的 HTTP 客户端。输出仅回传摘要和用量，不自动复制全部父会话。

## 验收

- 当前请求没有 usage 时明确显示估算，缺失缓存字段显示未知
- 切换聊天、发送下一轮、取消时不会串用上一次的单请求数据
- Token 用量不溢出，缓存不重复计入输入；DeepSeek 顶层命中字段可识别
- 子任务完成、异常、超时、取消都释放独立资源，不能停止父任务的前台服务
- 递归委托与超预算批次在执行前拒绝；所有子任务工具仍经过原权限检查
- 提供回归测试、GitHub 提交以及实际构建状态，未验收不标记完成

## 当前增量

[子任务详情、对话进度收尾与重复压缩](05-progress-details-and-summary-lifecycle.md) 记录 `feat/plan-steps-and-subagents` 的状态圈、两种输入布局详情、运行态投影与压缩生命周期修正。

[移除底部入口与工具标题对齐](06-input-removal-and-header-alignment.md) 记录最新 UI 调整，替代上一增量的输入框详情入口；状态圈左缘对齐工具展开箭头右侧的数量文字。

[精细三点状态与可折叠子任务](07-small-dots-and-collapsible-progress.md) 将对话区状态圈改为 14dp 占位的小三点动画和计划同款完成勾选，复用思考的折叠标题，输入框入口保持删除。

验证状态：`56c7494` 云端构建成功，完整 Debug JVM 测试有 5 个失败，根因待定位；最新 UI 调整的云端验证与真机验收待完成。