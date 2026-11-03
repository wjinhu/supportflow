# 用真实调用链讲 Java + AI 项目

先能演示 [四条业务流程](demo-guide.md)，再对照 [项目地图](project-overview.md) 阅读代码。面试中说清“输入是什么、谁校验、谁调用、状态存在哪、失败如何处理”，比罗列框架更有说服力。

本文提供可核对的项目表达，不代填个人贡献。只有实际完成、理解并能说明的部分，才能说“我实现了”。不要把学习项目、隔离实验或现有测试数量换成公司业务经历、线上流量和效果指标。

面试表述范围以[项目边界与证据](project-boundaries.md)为准。知识回答正文直接采用本地已发布内容或受控公开摘要，来源快照用于审计；这不等于任意私有知识的 LLM 生成。公开知识筛选与 Agent 规划的外发字段也不同，不能笼统承诺订单信息绝不外发。

当前按 Feature Freeze 收尾，主要任务转为理解和复述。最终 SHA 的 CI 证据、已修复的历史失败及 IMPLEMENTED / PARTIAL / NOT_IMPLEMENTED / NOT_NEEDED 范围统一见[能力审计](ai-job-gap-audit.md)。冻结不等于生产商用验收，也不要求继续堆框架。

## 一分钟项目介绍

> SupportFlow 是一个 Java / Spring Boot + Vue 的店铺客服学习项目。买家可以查询本人订单、物流和退款进度，商家负责知识维护、人工审批与人工接待。核心设计是把模型决策放进 Java 可校验的业务边界：模型可以理解问题或提出只读动作，订单归属、店铺授权、售后期限和审批仍由后端判断。物流 Agent 有持久化检查点、步数和费用预算、有限只读重试；知识回答保留来源和版本，支持可选的本地 BGE 检索。退款经人工批准后由持久化任务生成本地模拟回执。目前是单体实验系统，默认交付演示关闭付费模型，还没有真实支付和生产容量结论。

接着选一条自己最熟悉的调用链展开，不把四条都背成清单。

## Java 后端岗位：优先讲这三个问题

| 面试问题 | 项目里的具体做法 | 原理与取舍 | 下一步去读 |
| --- | --- | --- | --- |
| 为什么输入订单号不能直接查？ | 身份来自认证；SQL 同时约束店铺、买家、订单号，并过滤买家删除标记 | 参数定位资源，认证与授权决定可见性；前端藏按钮和模型承诺都不能替代 SQL 范围 | `OrderController → OrderQueryService → JdbcSupportOrderRepository` |
| 重复提交退款为什么不会生成多笔？ | 由买家、店铺、订单内部 ID 生成申请幂等键，唯一约束与事务保护插入；已有申请返回原记录 | 先查再插本身挡不住并发，数据库唯一约束才是最终防线；当前业务明确一单一申请，不支持多轮售后 | `AfterSaleService.submit` 和售后 Repository |
| 审批后为什么还需要执行任务？ | 批准与任务入队同事务；worker 领取任务，校验租约、审批、订单与金额，再保存模拟回执 | 把“业务批准”和“处理完成”分开；租约及幂等回执防止恢复时重复处理。当前是数据库任务，不是 MQ | `AfterSaleService.decide → AfterSaleExecutionProcessor → SimulatedRefundGateway` |

可直接说：“一个买家是否能操作，不是 Controller 接收到参数就算通过。归属、状态与权限校验在业务服务和持久层继续成立，写操作还要考虑并发与重试。”

不要把当前模拟事务扩大成支付系统的端到端 exactly-once。真实支付接入还需要支付方幂等键、超时查单、回调验签、对账和补偿，属于后续设计。

## AI 应用岗位：优先讲这三个问题

Spring AI 2.0.2 接入为 IMPLEMENTED。面试时可说明 ChatModel/ChatClient、只读工具 schema、结构化输出和 DocumentRetriever 如何接入原业务，而持久化 Loop、权限、预算和重试继续由 Java 运行时保证。核心链路是 `AgentLoopExecutor → SpringAiAgentDecisionPlanner → DeepSeekChatModel → DeepSeek API → validateDecision → 只读工具 → MySQL Checkpoint → 下一轮`。具体证据与未完成范围见[集成说明](spring-ai-integration.md)和[能力审计](ai-job-gap-audit.md)。

### 为什么保留确定性工作流，还需要只读 Agent？

规则明确的退款期限、归属与审批，用 Java 最容易保证边界。Agent 适合在允许的只读动作间，根据真实观察结果决定下一步，例如先查订单，再按订单状态查物流或结束。模型只提议动作，执行器检查动作是否合法、订单是否匹配、预算是否足够，最终回答必须有工具事实。

当前 `AssistantAgent` 也有确定性的路由和售后表单路径，不能把所有聊天请求都叫多步 Agent。只有物流持久化运行使用观察驱动循环；默认离线规划器走同样控制路径。这种范围较窄，但能把状态、恢复和失败规则讲清楚。

可以直接说：“LLM 负责不确定的理解、受限规划和动作建议；Java 负责身份授权、业务规则和真实执行。Observe → Decide → Validate → Execute → Checkpoint 的状态与预算在 MySQL；退款是确定性 Workflow + 人工批准，模型没有自主退款权限。”

### 为什么不是保存聊天历史就能恢复？

历史用于展示过去，检查点用于决定还允许执行什么。`ConversationService` 先保存本轮处理权，模型调用期间不持有外层事务；运行时保存已完成的工具观察、待执行动作、处理代次和预算。恢复前复核当前权限，旧 worker 不能覆盖新代次，已保存事实不必从头查询。

预算也必须持久化。模型调用前预留 Token 和估算费用，拿到有效 usage 再结算；结果不明时不能假装没花钱而退回预算。重启不会让截止时间、步骤或预算重新开始。读失败的有限重试与退款写入分开，熔断限制持续故障时继续请求供应商。

证据：[AgentLoopPersistenceIT](../src/test/java/dev/supportflow/assistant/runtime/AgentLoopPersistenceIT.java) 覆盖恢复、预算、有限重试和旧 worker；[AgentBudget](../src/main/java/dev/supportflow/assistant/runtime/model/AgentBudget.java) 定义预留与结算。这里的金额是按配置费率估算，不是供应商账单。

### RAG 怎么保证资料和权限都没过期？

先确定当前店铺可见的已发布来源，再做关键词／词法 + 本地 BGE dense 检索，经加权 RRF 类融合、阈值和词法回退得到 TopK 证据。向量缓存按店铺、来源版本、正文哈希及块号区分，持久索引另带模型指纹；即使命中也复核当前发布与可见性，旧向量不能重新开放已下线文章。用户只看回答正文，来源、版本和完整条件保留在审计快照，避免只抽出一句正面承诺。现有融合排序不是独立 Reranker，自有 MySQL 向量存储也不是 Spring AI VectorStore。

公开参考筛选可以交给 DeepSeek，但候选必须与受控公开目录精确匹配。私有或混合候选不外发；模型只选已提供的来源 ID，Java 做二次校验。商家知识检索留在本地。RAG 能补充依据，不能保证“零幻觉”；当前对业务事实仍以 Java 工具结果为准。

证据：[KnowledgeService](../src/main/java/dev/supportflow/assistant/knowledge/KnowledgeService.java)、[DeepSeekPublicKnowledgeSelector](../src/main/java/dev/supportflow/chat/infrastructure/deepseek/DeepSeekPublicKnowledgeSelector.java)、[PersistentKnowledgeVectorIT](../src/test/java/dev/supportflow/assistant/knowledge/PersistentKnowledgeVectorIT.java)。向量持久化测试使用合成向量，真实 BGE 的检索效果应看独立评测。

## 人工接待体现了什么后端能力

买家申请 `WAITING`，客服接待进入 `ACTIVE`，结束变为 `CLOSED`。同一买家与店铺只能有一场有效人工服务，数据库唯一约束和统一处理权范围配合；同范围的新智能回复必须被阻止。客服有 `CHAT_SUPPORT` 还不够，每次还检查有效员工和店铺授权，只有接待本人能读本次人工消息。

人工消息是纯文本记录，不会转成退款命令。关闭后保留历史，下一轮智能回复清除相关的旧结构化主题。前端用轮询获取消息，因此能讨论轮询开销与后续 WebSocket 的连接管理，但不能说已经实现 WebSocket、抢单分配平台或坐席转接。

转人工要求先返回结构化 `HUMAN_SUPPORT` 提示和按钮，点击才创建人工服务。空闲关闭按双方最后一次成功发消息计算 5 分钟，服务端锁定后重新检查时间；读取或重试旧消息不续期。关闭与释放智能处理权在事务内完成，评价只有本人能提交一次。这些边界可通过现有人工 API 测试解释并发与幂等。

发货短追问使用上下文定位订单、重新校验权限并查最新状态；消费金额则在 SQL 中汇总认证买家跨店近 30 天创建、当前为 `PAID/SHIPPED/COMPLETED` 的 CNY 订单，包含隐藏订单且未扣退款，不能讲成按支付日期计算的实付净额。售后知识可以解释流程，不能改变数据库里的资格、金额和审批结果。

证据：[HumanSupportService](../src/main/java/dev/supportflow/chat/support/HumanSupportService.java)、[HumanSupportApiIT](../src/test/java/dev/supportflow/chat/support/HumanSupportApiIT.java)、[V22](../src/main/resources/db/migration/V22__human_support_handoff.sql)。

## 被问到“效果、性能、上线了吗”

| 问题 | 可以准确回答什么 |
| --- | --- |
| 模型准确率多少？ | 真实 Spring AI 归档报告为 6/6 合成任务、14 次调用、10081 token，Tool Selection Accuracy / Argument Accuracy / Task Success Rate 均为 1.0；仅限小样本合成评测，不代表生产准确率或生产延迟基线，不能简称“Agent 准确率 100%” |
| 有多少用户或订单？ | LAB 有店铺 100、买家 200 的 5000 笔实验种子订单；这不是线上用户量、吞吐或业务规模 |
| 能承受多少并发？ | 当前有身份限流、模型并发/预算边界、连接池与指标；容量要固定环境压测，报告错误率、分位延迟和瓶颈，不能由配置推断 |
| CI 是否通过？ | 给出具体提交对应的 Actions run、各任务结果和交付物。新增工作流或本地测试通过不等于远端已经运行通过 |
| 交付了什么？ | 经校验的后端、前端、部署配置和脚本；可选择带镜像的离线包，使用独立 LAB 数据库运行。它是本机 Linux 容器实验部署 |
| 后续怎么扩展？ | 先测数据库、模型等待、检索耗时和连接占用；有证据后决定缓存、异步任务或拆分。Redis 已用于可选共享登录；全局限流、MQ、微服务和高可用仍待实现 |

Redis 只共享 HTTP Session，Agent State / Checkpoint / Budget 和聊天历史在 MySQL，没有 Redis ChatMemory 或 Redis Vector Store。SSE 输出的是已校验、已持久化最终回复的分段，不是模型 token 直出。Memory、规则式 Query Rewrite、VectorStore / Document、Streaming、安全注入防御、全局限流、Evaluation、Observability 和 Cost / Token Budget 的局限应主动说明；独立 Reranker 未实现，MQ / MCP 当前不需要。承认这些边界不会削弱项目，能说明什么由证据支持才是面试重点。

面试前最后确认三件事：能从页面请求找到一个 Controller；能指着 Service 解释一次权限或状态校验；能指出一个测试证明失败边界。若其中一项还讲不明白，先读通这一条链，再扩大项目介绍范围。
