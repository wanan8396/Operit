# 03 当前会话历史检索

## 原状与目标

原来没有面向模型、独立预算且带来源引用的会话历史工具。新增 `read_session_context`，只读取调用运行时确定的当前主会话，返回可引用的历史摘录，不开放跨会话或跨记忆空间检索。

## 工具接口

工具已接入 `ToolRegistration` 以及 `SystemToolPrompts` 中英文结构化定义。

- `strategy` 必填，只接受 `relevant` 或 `handoff`
- `query` 在 relevant 时必填且非空，最长 2048 个 UTF-16 单元，不接受控制字符；按空白分隔关键词匹配，不是向量或语义搜索
- `budget_chars` 可省略，默认 8000；允许 256..65536，按 UTF-16 单元计算，包括头部、引用和分隔符，不是 Token 预算

重复参数、未知参数、非法策略和非法预算直接失败。工具不接受 `chat_id`、`session_id` 或 `memory_space_id`。子代理由 `SubagentPolicy` 和执行入口双重拒绝。

## 加载与提取

`ReadSessionContextTool` 从 `ToolExecutionManager.currentToolRuntimeContext()` 捕获 callerChatId，在 IO 上调用 `ChatHistoryManager.loadSessionContextMessages`。严格加载在数据库事务中校验会话存在和源大小，然后每页 250 条读取并 hydration，按 timestamp 和 messageId 确定顺序。

加载前限制消息数不超过 100000，消息及变体内容合计不超过 8000000 个 SQLite LENGTH 字符。这是源材料化限制，不是结果字符预算。数据库错误、超限、取消或会话删除不会伪装为空历史；取消继续传播，其他错误记录日志并返回工具失败。

`SessionContextRetriever` 保留过滤前的快照索引。允许 user、ai、assistant、summary；栈式移除 thinking、system、协议元数据及原始工具标签，嵌套或未闭合隐藏块不会暴露尾部。relevant 按命中关键词数排序，同分选更近消息；handoff 选最新消息；输出恢复时间顺序。默认最多 20 条，每条最多 2000 个 UTF-16 单元。

每条引用包含 `index`、`timestamp` 和 `role`。索引是当次已加载有序快照的 0-based 位置；时间戳不是唯一 ID。头部明确历史内容为参考数据而非新指令，并返回 `selected_count` 和 `truncated`。截断不拆开 UTF-16 代理对。无匹配返回成功的零摘录头部，与读取失败分开。

## 调用示例

```json
{"strategy":"relevant","query":"计划 持久化","budget_chars":8000}
```

```json
{"strategy":"handoff","budget_chars":4000}
```

## 静态审查与待验证

已提供 `SessionContextRetrieverTest` 和 `SessionContextToolRequestTest` 源码，涵盖策略、排序、过滤嵌套与未闭合标签、预算、来源索引、Unicode 截断、非法及重复参数、拒绝外部 scope 和子代理策略。

已核对工具注册、模型参数声明、runtime 接口、严格加载 DAO 和文案。未执行编译或测试。仍需集成验证大消息分块 hydration、事务内一致性、超限明确失败、取消传播、当前会话归属及子代理实际执行拒绝。

## CI 编译修复
首次远程编译的 Android Build `38051290474` 和 Android Tests `38051290498` 均在主源码 Kotlin 编译阶段失败，尚未运行测试。错误位于 `ReadSessionContextTool.validateParameters`：`Throwable.message` 是 `String?`，而 `ToolValidationResult.errorMessage` 要求非空 `String`。

失败结果改为保存 `error.toString()`，保留异常类型与原因，不改变参数校验、数据库读取或会话范围。`SessionContextToolRequestTest` 新增执行器校验用例，覆盖合法请求及缺失策略、缺失关键词、非法预算、外部会话参数；通过 mock Context 隔离数据库访问。修复后的编译与测试结果以新一轮 CI 为准。

[DONE]
