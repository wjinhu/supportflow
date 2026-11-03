# SupportFlow

Java 21 / Spring Boot 4 + Vue 店铺客服学习项目，包含可切换 Spring AI 2.0.2 DeepSeek planner、Hybrid Retrieval / BGE、Durable Agent Runtime、MySQL 和 optional Redis Session。买家查本人订单与物流、咨询规则、申请退款，商家维护知识、人工审核并接待咨询。模型参与受限决策，Java 控制身份、权限、状态与执行边界，知识来源保留用于审计。

`supportflow.ai.planner=custom` 当前仍为默认值。Spring AI 模式需显式开启；只有受限模型决策与公开资料筛选经过该适配，具体业务事实由原 Java 运行时校验和执行。

[![SupportFlow CI and delivery](https://github.com/wjinhu/supportflow/actions/workflows/agent-evaluation.yml/badge.svg?branch=main)](https://github.com/wjinhu/supportflow/actions/workflows/agent-evaluation.yml?query=branch%3Amain)

本版进入 Feature Freeze 验收：保持现有功能和 AI 主架构，后续优先学习、演示和面试复述。冻结依据是最终提交对应的全部必需 CI 作业成功；已核验基线、修复历史和剩余局限见[能力审计](docs/ai-job-gap-audit.md)。

## 从这里开始

| 想做什么 | 入口 |
| --- | --- |
| 下载可运行交付包 | 仓库 Actions → **SupportFlow CI and delivery** → 成功的 main 推送或手动 run → Summary / Artifacts；先看 [安装与包校验](docs/delivery-install.md) |
| 五分钟展示买家到商家的闭环 | [演示步骤](docs/demo-guide.md)：隔离 LAB、双浏览器、默认关闭付费模型 |
| 从业务看懂代码 | [项目地图与调用链](docs/project-overview.md)：角色、输入输出、谁调用谁、数据库边界 |
| 准备 Java / AI 应用面试 | [面试指南](docs/interview-guide.md)：设计取舍、失败处理、证据与表述范围 |
| 核对本版完成范围 | [项目边界与证据](docs/project-boundaries.md)：Java／模型、数据外发、模拟业务及验证范围 |
| 沿用本机开发环境 | [前端与联调](frontend/README.md)、[日常演示数据](docs/local-demo-data.md)、[源码 Compose 实验](deploy/README.md) |

交付包包含后端、前端、隔离部署脚本与校验信息；需要可离线导入的运行镜像时，在手动工作流中选择 `include_images=true`。以具体 run 的结果为准，新增工作流或本地验证通过不代表云端已经通过。工作流见 [CI 配置](.github/workflows/agent-evaluation.yml)。

## 能力与边界

已有店铺/买家隔离、商品名查单与商品图、每店聊天窗口、退款原因与收货后 15 天规则、人工审批和持久化模拟退款；也有数据库账号、服务端会话、限流与追踪、只读 Agent 检查点/预算/有限重试/熔断、可选 BGE 与持久向量、TXT/MD 知识草稿发布、独立权限的人工接待。

聊天支持选单后的发货短追问、本人跨店近 30 天消费汇总、转人工提示按钮和售后说明知识。消费口径为近 30 天创建、当前处于已付款／已发货／已完成状态的 CNY 订单金额，未扣退款，包含买家已隐藏订单；并非按支付日期计算的净消费。人工接待在双方最后一条成功消息后空闲 5 分钟自动结束，随后可评价；资料来源留作审计，用户只看回答正文。

核心分工是 **LLM 处理不确定的理解与受限规划，Java 控制授权、业务规则和真实执行**。Agent 按 Observe → Decide → Validate → Execute → Checkpoint 循环，State / Checkpoint / Budget 均在 MySQL。退款是确定性 Java Workflow + 人工审批。SSE 是已校验并保存的最终回复分段传输，不是模型首 token 直出。

Spring AI、受限 Agent Loop、Hybrid RAG 和工程交付已有实际实现。Memory、Query Rewrite、VectorStore / Document、Streaming、Prompt Injection、Rate Limit、Evaluation、Observability 和 Cost / Token Budget 仍有明确局限；没有独立 Reranker，MQ / MCP 当前无需引入。真实 Spring AI 评测仅为 6 个合成任务、14 次模型调用的小样本，不能用作生产准确率或生产延迟基线，详见[能力审计](docs/ai-job-gap-audit.md)。

当前为单体学习系统，支持显式启用 [Redis 共享登录会话](docs/redis-session.md)；全局限流和模型并发仍按实例计数。物流读取本地记录，退款只产生模拟回执；未实现真实支付、微服务、MQ、WebSocket 或 PDF/OCR。交付包中的隔离演示默认关闭 DeepSeek 和 BGE，不会自动调用付费模型；源码 Compose 默认开启本地 BGE，须先准备并校验模型。模型 Key 只保存在后端私密配置。

启用 DeepSeek 后，物流 Agent 规划可能发送用户问题、订单号和裁剪后的订单／物流观察；公开资料外发限制只针对知识筛选入口。完整说明和逐入口字段见[项目边界](docs/project-boundaries.md#deepseek-到底接收什么)。

## 专项文档

- 框架接入与岗位能力核对：[Spring AI 2.0.2 集成](docs/spring-ai-integration.md)、[Java AI 能力审计](docs/ai-job-gap-audit.md)。默认保留原规划器，可切换 Spring AI，业务执行、授权与持久化运行时沿用原实现。

- 业务：[商品名查单](docs/product-order-lookup.md)、[订单删除标记](docs/order-deletion.md)、[商家授权](docs/merchant-workspace.md)、[售后执行](docs/after-sale-execution.md)、[人工接待](docs/human-support.md)。
- AI 与状态：[Agent 循环与预算](docs/agent-loop-budget.md)、[模型保护](docs/agent-resilience.md)、[会话上下文](docs/conversation-context.md)、[语义检索](docs/semantic-rag.md)、[离线评测](docs/agent-evaluation.md)。
- 知识与工程：[店铺知识发布](docs/shop-knowledge-management.md)、[文件导入](docs/knowledge-file-import.md)、[工程补全](docs/job-readiness.md)、[部署与容量实验](docs/deployment-and-capacity.md)。
- 接口：[店铺聊天](docs/shop-chat.md)、[HTTP 示例](chat.http)；图片来源见 [商品图片说明](docs/product-images.md)。
