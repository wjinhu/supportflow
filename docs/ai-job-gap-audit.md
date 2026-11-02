# Java AI 应用岗位能力审计

审计对象为当前本地工作树，包含此前尚未提交的会话流式、商品追问和可选 Redis Session，以及本次 Spring AI 适配。状态依据实际代码和测试入口，不以依赖存在代替业务接入，不代填个人贡献、线上规模或招聘录用结论。

状态仅使用 IMPLEMENTED、PARTIAL、NOT_IMPLEMENTED、NOT_NEEDED。前者表示下述明确范围已有实现，PARTIAL 表示仍有实质缺口。NOT_NEEDED 表示当前规模和功能没有引入收益。本表的岗位价值与优先级是针对该项目的工程判断。岗位对照见 [美团 AI 应用开发校招](https://zhaopin.meituan.com/web/position/detail?highlightType=campus&jobShareType=1&jobUnionId=4694862735)、[百度 Agent 应用全栈校招](https://talent.baidu.com/jobs/detail/GRADUATE/6f9c3a86-6557-409d-8fa7-e6f4c68d6765)。

表内源文件在 `src/main/java/dev/supportflow`、测试在对应 `src/test/java/dev/supportflow` 下。P1 为下一阶段优先验证，P2 为有数据支持后推进，P3 为当前展示范围之外。

| 能力 | 状态 | 当前实现 | 证据类/文件 | 是否真实运行 | 面试价值 | 缺口 | 建议优先级 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Spring AI ChatClient | IMPLEMENTED | 公开参考筛选业务 SPI；显式关闭自动工具 advisor | `ai/springai/SpringAiConfiguration`、`SpringAiKnowledgeAdapterTest` | 实际 SDK + 合成 provider；未调付费模型 | 解释框架集成与外发边界 | 代表性真实模型验收待做 | P1 |
| ChatModel | IMPLEMENTED | DeepSeekChatModel 驱动原 AgentDecisionPlanner | `SpringAiAgentDecisionPlanner`、`SpringAiAgentRuntimeIT` | SDK 与 MySQL 闭环测试 | 协议层与运行时解耦 | 供应商真链路兼容待独立验收 | P1 |
| Structured Output | IMPLEMENTED | BeanOutputConverter 映射参数与公开 ID record；严格 JSON 预检和业务后检 | `SpringAiToolDefinitions`、`SpringAiKnowledgeAdapter` | 异常 JSON/重复键/非法动作测试 | 区分格式约束与业务合法性 | 更大自然语言覆盖集 | P1 |
| Tool Calling | IMPLEMENTED | 模型返回一个动作，原 Java 执行器执行 | `AgentLoopExecutor`、`SpringAiAgentRuntimeIT` | SDK/数据库回归 | 可解释权限、顺序和副作用 | 多写工具暂不开放 | P2 |
| Tool Schema | IMPLEMENTED | 按状态暴露只读 schema；只允许 orderNo | `SpringAiToolDefinitions` | 请求内容和 callback 拒绝测试 | 最小暴露面 | 未接自由工具注册平台 | P3 |
| Tool Context | NOT_NEEDED | 使用原认证主体和运行 key 传递可信上下文；未启用 Spring AI ToolContext | `AgentRunKey`、`AgentLoopExecutor.requireOwner` | 身份伪造和归属测试 | 解释可信/不可信参数来源 | 如接框架执行器再设计桥接 | P3 |
| Agent Loop | IMPLEMENTED | Observe/Decide/Validate/Execute/Checkpoint | `AgentLoopExecutor`、`AgentLoopPersistenceIT` | 真实 MySQL；模型为规则/合成响应 | 控制循环、顺序和终止 | 仅订单/物流只读任务 | P2 |
| Agent State | IMPLEMENTED | 观察、待执行动作、workflow version | `AgentLoopState`、`JdbcAgentRunStore` | 持久化回归 | 状态机与并发一致性 | 未实现多 Agent 分支 | P3 |
| Checkpoint | IMPLEMENTED | Generation/Lease、防旧 worker 覆盖、恢复不重置预算 | `AgentLoopPersistenceIT`、`SpringAiAgentRuntimeIT` | MySQL 恢复测试 | 故障恢复与 fencing | 未做多节点故障演练 | P2 |
| Memory | PARTIAL | MySQL 历史、有限指代、TTL、敏感动作不继承 | `ConversationContextEngine`、`ConversationApiIT` | 单元及 API 回归 | 连续性与授权边界 | 无语言摘要型长期记忆 | P2 |
| RAG | IMPLEMENTED | 当前可见资料检索与完整证据回答；可选公开 ID 筛选 | `KnowledgeService`、`RagKnowledgeSearch` | 检索和向量回归；新适配测试 | 知识新鲜度与可追溯性 | 私有资料不外发生成，不能宣称任意知识生成 | P1 |
| Hybrid Search | IMPLEMENTED | 关键词+BGE、加权 RRF 类融合、阈值与回退 | `RagKnowledgeSearch`、`PersistentKnowledgeVectorIT` | 合成向量集成；真实 BGE 效果见独立报告 | 召回与精度取舍 | 缺更大人工标注消融实验 | P1 |
| Reranker | NOT_IMPLEMENTED | 现有融合排序，未接 cross-encoder | `RagKnowledgeSearch.search` | 无独立重排模型运行 | 解释何时需要二阶段排序 | 先测错误来源再决定模型 | P2 |
| Query Rewrite | PARTIAL | 本地规则与同义词扩展 | `KnowledgeQueryExpansion` | 规则回归 | 查询改写的作用与误改风险 | 无学习式改写与效果比较 | P2 |
| VectorStore / Document | PARTIAL | MySQL 持久向量；Spring AI DocumentRetriever 与 Document 适配 | `JdbcKnowledgeVectorStore`、`SpringAiKnowledgeAdapter.forShop` | 向量及适配测试 | 抽象与可见性过滤顺序 | 无 ANN 引擎及大语料容量结论 | P2 |
| Streaming / SSE | PARTIAL | 已保存 Java 最终回复的 SSE 分段与恢复 | `ConversationStream`、`ConversationController` | SSE 回归 | 结果持久化和断连处理 | 无 LLM token-stream；当前收益不足以改写事实链 | P3 |
| Guardrails | IMPLEMENTED | 动作白名单、参数、顺序、预算、身份、当前归属校验 | `AgentLoopExecutor.validateDecision`、Spring AI 专项测试 | 回归 | 后端强制边界 | 范围仅当前业务 | P2 |
| Prompt Injection | PARTIAL | 数据与指令分离、拒绝额外字段、私有资料外发限制、无写 callback | `DeepSeekPublicKnowledgeSelector.permits`、Spring AI 测试 | 定向恶意样例 | 分层防御 | 无系统化红队评测或通用 PII 网关 | P1 |
| Human-in-the-loop | IMPLEMENTED | 售后独立权限人工审核后进入持久化模拟执行 | `AfterSaleService`、`AfterSaleExecutionProcessor` | API/并发/执行回归 | 审批与执行分离 | 未接真实支付 | P3 |
| Timeout | IMPLEMENTED | 原调用总超时、完整 body 超时、run deadline 与 lease | `DeepSeekCallExecutor`、Spring AI 超时测试 | 超时/截止测试 | 区分调用与任务时限 | 未测全网故障矩阵 | P2 |
| Retry | IMPLEMENTED | SDK 零重试；原持久化策略有限重试 | `SpringAiConfiguration`、`SpringAiAgentRuntimeIT` | 请求次数和预算验证 | 防止乘法重试 | 写操作独立设计 | P2 |
| Circuit Breaker | IMPLEMENTED | 新旧适配共用原熔断器 | `DeepSeekCircuitBreaker`、Spring AI 熔断测试 | 故障注入回归 | 避免故障放大 | 当前按实例 | P2 |
| Rate Limit | PARTIAL | 认证身份和模型配额、模型并发上限 | `common/traffic`、`DeepSeekCallExecutor` | 回归 | 配额、并发与预算差异 | 无跨实例全局配额 | P2 |
| Cache | IMPLEMENTED | 有界进程内向量缓存与持久向量，版本/正文 hash 分离 | `RagKnowledgeSearch` | 向量变更回归 | 缓存不替代可见性判定 | 无大型缓存容量报告 | P2 |
| Redis | IMPLEMENTED | 可选共享浏览器登录会话 | `RedisSessionConfiguration`、`RedisBrowserSessionIT` | 已有隔离测试；非全量状态迁移 | Session 共享与撤销 | 限流/熔断仍按实例；ACL 调整另议 | P2 |
| MQ | NOT_NEEDED | 当前 MySQL 持久化任务满足模拟售后恢复 | `AfterSaleExecutionProcessor` | 已有任务回归 | 讨论何时需异步解耦 | 流量/消费者需求出现再评估 | P3 |
| MCP | NOT_NEEDED | 固定项目内工具不需要外部工具发现协议 | `OrderTool`、`LogisticsTool` | Java 工具链可运行 | 区分工具协议与业务工具 | 暂无跨应用工具共享需求 | P3 |
| Evaluation | PARTIAL | 离线场景回归、独立真实模型规划样例报告 | `AgentScenarioEvaluationIT`、`docs/real-model-evaluation.md` | 现有离线测试；本次无新付费评测 | 避免只报测试数量 | Spring AI 模式真实端到端标注集待做 | P1 |
| Observability | PARTIAL | 原指标与阶段日志、Spring AI ObservationRegistry 接入 | `SpringAiConfiguration`、`DeepSeekCallMetrics` | SDK 指标/日志测试与原回归 | 成本、失败归因 | 无统一跨服务 tracing exporter | P2 |
| Metrics / Logs / Traces | PARTIAL | 固定标签、traceId/agentRunId、步骤数据和 usage | `StageTrace`、`TraceContext`、Spring AI 适配 | 安全失败与调用计数验证 | 按层定位故障 | 缺面向任务的统一看板与告警基线 | P2 |
| Docker | IMPLEMENTED | 隔离 Compose 与交付镜像流程 | `deploy/compose.yml`、部署文档 | 原部署证据；测试使用容器 | 环境复现与交付 | 本次受占用 JAR 影响未重新构建整套镜像 | P2 |
| CI | IMPLEMENTED | Maven verify、前端/浏览器和交付工作流 | `.github/workflows/agent-evaluation.yml` | 本地验证；本次未推送触发远端 | 可追溯交付 | 本次提交远端运行待用户后续发布 | P2 |
| Integration Test | IMPLEMENTED | 真 MySQL/Redis 容器、API、安全、恢复与业务并发 | `src/test/java`、`SpringAiAgentRuntimeIT` | 本地隔离验证 | 测试分层与行为证据 | 合成模型不能替代供应商验收 | P1 |
| Security | IMPLEMENTED | DB 账号、服务端会话、CSRF、安全版本、独立业务权限 | `common/security`、安全 API IT | 已有与新增回归 | 认证、授权、撤销 | 未做外部安全审计 | P2 |
| Multi-tenant isolation | IMPLEMENTED | 订单/会话/商家授权/检索绑定店铺与认证主体 | `OrderQueryService`、`MerchantAccessService`、`KnowledgeService` | 越权与隔离回归 | 防水平越权与知识串店 | 无跨租户容量隔离实验 | P2 |
| Idempotency | IMPLEMENTED | 会话消息 key、售后申请唯一约束、调用 ID 与租约 | `JdbcConversationStore`、`AfterSaleService`、`AgentLoopExecutor` | 并发和恢复回归 | 并发正确性 | 真实外部支付幂等不在当前范围 | P2 |
| Cost / Token Budget | PARTIAL | 持久化预留、usage 结算、未知结果保守记账 | `AgentBudget`、`SpringAiAgentRuntimeIT` | Token 与恢复回归 | 成本约束与不确定结果处理 | 费率估算非账单，需真实成本质量比较 | P1 |

## 最高优先级的五项工作

1. 建立 Spring AI 模式的代表性真实模型端到端评测，覆盖正确调用、含糊问题、指代、拒绝、供应商异常。保留输入、期望动作、实际调用、成功率与失败归因。执行付费评测前单独确认环境、数据范围和成本。
2. 更新 Agent 与知识路径的性能基线，测 p50/p95、模型/数据库/检索耗时、Token 与失败率。原订单读取压测不能替代整条 Agent 路径的容量证据。
3. 扩充检索标注集和消融实验，比较关键词、dense、hybrid 的召回和答案依据，确定是否真的需要 reranker 或学习式 query rewrite。
4. 增加系统化安全评测，覆盖恶意商品资料、跨店同名商品、伪造来源、模型注入和未知结果恢复。将目前定向测试扩展为可持续回归集。
5. 汇总任务级可观测性与交付验收，将一次任务的阶段、模型请求、Token、工具结果和失败原因关联展示。可执行 JAR 已验证；后续完成当前版本整套容器和离线交付包验收，再按需求决定是否接 trace exporter 或全局配额。

这些任务分别补效果、性能、检索质量、安全覆盖和可运营证据。当前没有证据要求增加 MQ、微服务、WebSocket 或 MCP。

## 本次验证记录

验证日期为 2026-11-02。新模式的自动化测试使用合成 provider 响应，不连接真实 DeepSeek，不能据此推断真实模型准确率或费用。

| 验证 | 结果 | 范围 |
| --- | --- | --- |
| `mvn -B -ntp test` | 803 项，零失败/错误/跳过 | 第一轮完整单元回归 |
| `mvn -B -ntp "-Dspring-boot.repackage.skip=true" verify` | 805 单元 + 437 集成，零失败/错误/跳过，BUILD SUCCESS | 修正配置文件被替换时的自动装配后，运行全部 29 个 IT 类 |
| `mvn -B -ntp "-Dit.test=SpringAiAgentRuntimeIT" "-Dspring.test.context.cache.maxSize=4" test failsafe:integration-test failsafe:verify` | 最终源码 807 单元 + 6 项 Spring AI 集成，全部通过 | 包括最后增加的 SDK Token 观测和数字 callId 强制类型校验 |
| Spring AI 专项合计 | 44 单元 + 6 集成，全部通过 | 实际 SDK/RestClient、合成 HTTP 响应、真实 MySQL 容器 |
| 独立目录可执行 JAR 打包 | BUILD SUCCESS，Java 21 / Boot 4.0.8 / Spring AI 2.0.2 | 核对 JarLauncher、SupportFlowApplication 和实际嵌套依赖；复制回常规输出后 SHA-256 一致 |
| `git diff --check` | 通过 | 本次已跟踪文件差异的空白检查 |

普通 `verify` 首次因 Windows 占用已有 JAR 的 `.original` 文件而无法重打包。测试阶段采用仅跳过 repackage 的参数继续，随后在 `target/spring-ai-verification` 中使用同一份 POM 依赖与源码、独立输出目录完成可执行 JAR 打包。最终可执行文件为 `target/supportflow-backend-0.1.0-SNAPSHOT.jar`。临时构建文件和测试日志均被 Git 忽略。

第一轮全量集成暴露出独立测试配置不加载主 application.yml 时，starter 默认模型错误装配的问题。已在应用入口显式排除默认模型/客户端自动配置，重跑全部 437 项集成通过。

全量 verify 在测试完成后的 JVM 退出阶段出现一次 30 秒清理超时，线程栈位于 `SpringApplicationShutdownHook → HikariPool.shutdown → awaitTermination`。Surefire 清理了测试 JVM，Maven 退出码仍为 0；最终带 CI 同款上下文缓存上限的专项复检正常结束。此资源清理现象仍应单独排查，当前没有证据将其归因于 Spring AI SDK，也不将它隐藏为完全没有环境问题。

本次没有远端 CI 结果、真实付费模型验收、完整新容器交付验收或新增生产容量结论。默认仍为 custom；日常运行进程没有自动切换到新规划器。
