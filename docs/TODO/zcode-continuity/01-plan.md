# 01 会话计划持久化

## 原状与作用域

原 Store 仅在进程内保存计划和阶段，工具分两次更新。已核实 ChatViewModel 订阅 `plans` 并调用 `clear(chatId)`，MessageCoordinationDelegate 和 EnhancedAIService 按 chatId 读取模型阶段，计划工具使用 ToolExecutionManager 的 `callerChatId` 和 `isSubTask`。

作用域为两个计划 Store、PlanStatePersistence、PlanStepTools、计划测试源码与应用初始化，并通过 PlanContextInjection 和 EnhancedAIService 接入请求构造。

## 已实现

- 使用 Android AtomicFile 保存应用私有目录下的固定文件 `plan-state-v1.json`，chatId 只作为 JSON 键，不参与路径拼接
- 两个 Store 共享一个 Repository 和监视器；同一 chat 的 steps 与 phase 在一个快照、一份 JSON 和一个提交中保存
- 首次初始化同步读取全部会话，初始化完成前不提供计划访问；重复初始化不重载
- 空 steps 与非空 phase 保留在文件和快照中；原 `plans` Flow 仍只列出非空计划，避免改变 UI 行为
- 更新成功后才发布 Flow；调用者列表被复制，快照与 Flow 集合不能从外部修改
- `update_plan` 在写入前验证步骤、阶段并拒绝 `isSubTask`；`read_plan` 一次读取完整快照，子代理只读

## 接口与父调用方集成

应用启动调用 `PlanStepStore.initialize(context)`，现已位于 OperitApplication.onCreate。`PlanModelStageStore.initialize(context)` 是同源初始化入口。

原有 `PlanStepStore.update(chatId, steps)`、`read(chatId)`、`clear(chatId)`、`plans` 和阶段 Store 的 `read/update/clear/resolve` 保留。steps-only update 保留阶段，阶段 update/clear 保留步骤，计划 clear 同时清除两者。

模型同时修改两者时调用 `PlanStepStore.update(chatId, steps, stage)`，返回旧 steps。需要一致读取时调用 `readState(chatId)`，返回 `PlanStateSnapshot(steps, stage)`。

父调用方在构造父会话上下文时调用 `PlanStepStore.formatPlanContext(chatId)`。无计划且无阶段返回空字符串；否则返回权威 JSON、phase、禁止因总结或重启重复已完成操作的提示，以及子代理不得覆盖父计划的约束。EnhancedAIService 已在历史准备时移除旧 typed turn 并注入新快照，工具续轮也刷新计划，即使模型阶段未改变。PlanContextInjection 仅替换专属 metadata 标记的 system turn，不删除用户提供的相似文本。子代理上下文不得把父状态当作自己的可写计划。

## 确定失败语义

缺少文件是首次使用，产生空状态，不读取另一来源。读取错误、损坏 JSON、未知版本或非法字段抛出 PlanPersistenceException，应用启动不会假装恢复成功。

持久化写入同步刷盘，AtomicFile 完成提交后比较正式文件内容。I/O 或提交确认失败不发布新 Flow，并阻止 Repository 后续权威读取和更新，要求进程重启重新读取唯一来源。提交结果未知时不能继续把旧内存状态当作权威。Flow 保留最后已确认的 UI 值，不能据此判断失败写入是否已经落盘。

空 chatId 或空白步骤内容在提交前拒绝，不污染 Repository。工具把不可用状态返回为失败结果，不切换为纯内存存储。

## 静态审查与待验证

已补充 JVM 纯逻辑测试源码：重建恢复、多会话隔离、空列表阶段、旧接口读改写、清除、原子快照、提交失败和不确定提交、损坏存储、不可变集合、格式化上下文、非法输入、并发多会话及同 chat 两半更新。

本任务仅进行源码与 diff 静态检查，没有执行编译或测试。先前未完成输出中两个测试字符串的换行和引号已修复。

待验证事项：执行 JVM 测试与 Android 编译；真机进程重启及 AtomicFile 中断写入恢复；磁盘满、权限错误及提交确认失败；父上下文实际注入、摘要后续聊及子代理不污染父计划的集成行为。同步整份文件提交的磁盘延迟和大量会话规模也需设备评估。

计划持久化、请求边界接线和测试源码已实现；运行验证仍待完成。

[DONE]
