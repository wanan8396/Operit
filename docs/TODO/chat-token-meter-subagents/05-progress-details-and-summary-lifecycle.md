---
branch: feat/plan-steps-and-subagents
status: implementation-complete-validation-pending
---
# 子任务详情、对话进度收尾与重复压缩

## 1. 状态圈与输入区详情

旧状态圈有深色圆底，成功态仍为空心。输入区没有任务详情入口。

共享 `SubagentStatusCircle` 改为 12dp、1.5dp 细环，不绘制深色底；成功态为 `#34C759` 实心圆。其他状态使用主题前景、错误或次要前景色。

`ChatArea` 使用助手正文的局部起点，Cursor 为 16dp，带工具节点的 Bubble 使用展开布局起点及配置的正文左内边距。外层原有 horizontalPadding 不重复添加。

Agent 与 Classic 输入布局接入 `SubagentInputStatus`。点击标识打开实时更新、可滚动并可关闭的详情，显示任务名、状态、已发生重试次数和当前未结束工具。不显示子任务思考、原始工具输出或错误原文。聊天切换后关闭展开态。

[DONE] 源码实现；主题、壁纸、不同 padding 和输入布局的视觉验收待执行。

## 2. 分离对话投影与可查看详情

旧 `sessions` 保留全部结束任务，ChatArea 又始终渲染同一份列表，因此对话尾部长期驻留旧进度。

新增 `conversationSessions` 运行态投影。当前轮还有 running/retrying 任务时包含本轮全部任务，以呈现已完成的绿色圆；全部任务进入终态后移除该聊天的对话投影。原 `sessions` 保留本轮任务供输入区查看，下一轮开始或 clear 时清理。没有计时隐藏或回退渲染。

`ChatArea` 仅订阅对话投影，结束后释放尾部空间，后续工具继续使用已有消息渲染链。自动滚动依赖仍包含投影变化。

新增 `SubagentConversationProjectionTest` 覆盖四种终态、部分完成、重试、下一轮清理、过期轮次与 clear。

[DONE] 源码及回归测试编写；测试和连续工具流真机验收待执行。

## 3. 压缩边界与自动续聊

发现的源码问题：

- 阶段模型的工具续聊检查只取 contextLength，忽略 enableMaxContextMode 与 maxContextLength
- 摘要生成涵盖快照内全部 user/ai 消息，插入位置却只在最后一条 ai 后，尾部 user 原文重复进入压缩后上下文
- addSummaryMessage 插入被跳过时不返回结果，协调层仍标记成功并启动自动续聊
- 自动续聊前未检查重建请求是否仍然超过压缩阈值

`ContextSummaryPolicy` 统一有效窗口计算、摘要覆盖边界及续聊阈值判断。插入边界与生成快照一致。`addSummaryMessage` 返回实际写入结果，协调层在失败时明确报错，不启动自动续聊；异步发送摘要也检查插入结果。

摘要写入后以当前阶段模型配置和索引重算上下文。仍达到阈值时保留已写入摘要、结束该次自动续聊并提示缩减角色卡/工具提示/摘要或调整真实上下文窗口，不通过提高阈值或禁用压缩掩盖问题。超过新内容阈值时仍可正常再次压缩。关闭总结的请求不会被阶段路由重新开启。

新增 `ContextSummaryPolicyTest` 覆盖尾部用户消息、空历史、最大窗口、溢出和阈值相等/超过/低于的分支。

[DONE] 源码及纯逻辑测试编写；完整压缩链与数据库插入竞态验收待执行。

## 验证记录与后续验收

源码实现阶段未执行构建、测试或安装。`git diff --check` 通过；中英文资源 XML 均解析成功，六个新增字符串标识各出现一次。

用户现已要求上传 GitHub 编译。本次将提交到 `feat/plan-steps-and-subagents`，由已有 Android Build 推送触发器执行云端构建，同时为 Android Tests 添加该分支的推送触发。云端结果待运行完成后记录，本地仍不执行编译或下载 APK。

完整增量补丁与 HEAD 原文件快照保存在仓库外的 `operit-feature-work/artifacts/`，包含新增源码、测试与本文档。
Android Build 的定向测试列表加入 `SubagentConversationProjectionTest` 与 `ContextSummaryPolicyTest`；Android Tests 运行完整 Debug JVM 单元测试。提交、推送与云端运行结果以对应 GitHub Actions 记录为准。

建议使用仓库 `android-build.yml` 与 `android-tests.yml` 云端验收，并在真机复现：批量子任务部分完成及全部完成、紧接普通工具调用、输入框展开时实时更新、切换聊天、取消/超时、连续两轮子任务；压缩正常续聊、插入失败不续聊、压缩后仍超限明确停止，以及最大上下文模式与阶段切换。

当前对话供应商的工具输出截断属于独立的请求/上下文限制；应用源码修改不会改变本会话供应商的配置。分析阶段采用定向分段读取，避免用全文件工具输出反复扩大上下文。
