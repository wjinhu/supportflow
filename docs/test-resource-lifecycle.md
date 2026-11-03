# 测试资源生命周期收尾

本轮仅调整测试基础设施与资源验证，不更改 Agent 主循环、工具权限、状态/Checkpoint/Budget、重试所有权、RAG/BGE、人工审批或 Redis Session 职责。

## 根因与分类

基线使用完整 `mvn -B -ntp "-Dspring.test.context.cache.maxSize=4" verify` 复现。原先 15 个 `@SpringBootTest` 类同时采用类级 `@Testcontainers`，其静态 MySQL 容器在测试类结束时停止，而 Spring Context 仍保留在测试缓存中，直到后续 LRU 淘汰或 JVM 退出才关闭。

缓存中的 Hikari housekeeping/fill 任务因而继续验证和补充已经断开的数据库连接。日志中的 `Failed to validate connection`、MySQL `Communications link failure` 出现在后续测试运行期间，不只在 JVM shutdown。它们不能直接归为“只在 JVM 退出时可接受”的 `TEST_ONLY_SHUTDOWN_WARNING`，也不应通过降低日志级别处理。

这是**测试外部资源与 Context 生命周期错配**，不是当前证据所显示的业务 JDBC Connection/Statement/ResultSet 未归还。另发现一个独立的测试资源遗漏：`RedisBrowserSessionIT` 自己创建的静态 Java HTTP 客户端原先没有 close。它也属于测试所有权问题，不是服务端 HTTP Bean；后者原本已有 Spring `destroyMethod="close"`。

依据：[Spring Context 缓存生命周期](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)、[Testcontainers 类级容器生命周期](https://java.testcontainers.org/test_framework_integration/junit_5/)、[JUnit AfterAllCallback 语义](https://docs.junit.org/current/api/org.junit.jupiter.api/org/junit/jupiter/api/extension/AfterAllCallback.html)。源码配置、复现时序、关闭顺序回归与修复后复跑共同验证该判断。

## 修复方式

15 个 Spring/容器测试共同继承 `SpringContainerTest`。其继承的 JUnit `@AfterAll` 用户回调发生在 Testcontainers 的 extension `AfterAllCallback` 之前，先检查归还连接，再通过 Spring TestContext 的公开 API 将**同一个** Context 从缓存移除并关闭，然后才允许类级容器停止。

没有把互相独立的测试库改为共享库，没有减小连接池来掩盖问题，也没有依赖多个 extension 之间的注册顺序。关闭时核对同一缓存实例，避免误加载第二个 Context；关闭后确认缓存条目已经失效。

`RedisBrowserSessionIT` 保留手动启动两个应用的原设计，结束时分别调用同一资源核查辅助器，再关闭它自己创建的 HTTP 客户端。Redis 故障用例仍会主动停止 Redis 来验证 fail-closed；这条业务安全测试及其预期故障日志保留，不与 MySQL 清理告警混算。

## 只检查明确所有权的资源

`ApplicationResourceAssertions` 只保存已经实例化、由当前 Context 拥有的 singleton 引用，不触发懒 Bean，不遍历全 JVM 线程：

| 资源 | 关闭断言 |
| --- | --- |
| HikariDataSource | 关闭前最多等待 5 秒，active/borrowed connections 为 0；关闭后 pool closed、total connections 为 0 |
| ThreadPoolTaskExecutor / ThreadPoolTaskScheduler / ExecutorService | 持有原 executor 引用，Context close 后 terminated |
| SimpleAsyncTaskExecutor | close 后 inactive |
| Spring 托管 HttpClient | close 后 terminated |
| LettuceConnectionFactory | stopped，新的 getConnection 被拒绝 |
| 当前 Context 的 Lettuce ClientResources | 其 event executor group terminated |

关闭后的状态断言共用最多 5 秒等待。归还连接断言即使失败，也会在 finally 中关闭 Context；断言失败会使测试失败，不吞掉错误。没有按系统线程名字做脆弱断言，也不宣称扫描或证明了整个 JVM 的所有资源。

新回归包括：

- `ApplicationResourceLifecycleTest`：启动工作线程、调度器及原售后轮询配置（业务 worker 为替身），验证 Context close 后终止；另用原 `DeepSeekConfiguration` 创建真实 HTTP Bean，验证关闭，不发送远程请求。
- `SpringContainerShutdownOrderTest`：用确定性的外部资源替身验证“依赖 Bean 销毁 → Context 关闭/缓存失效 → 外部 AfterAllCallback”，无需增加一个数据库容器。
- 原 15 个真实 MySQL Spring 测试以及 Redis 多实例测试在正常结束时执行上述归还/关闭断言。

Agent 目前在请求线程执行，SSE 使用 Servlet 拥有的输出流；没有项目单独创建、需要人工 shutdown 的 Agent/SSE executor。售后轮询由 Spring scheduling 管理。Spring AI 的调用作用域已有 finally 清理 ThreadLocal；本轮没有修改这些业务语义。基于已审计的资源类型，未发现需要改生产实现的泄漏证据。

## 验收记录

原始日志保留在忽略目录 `target/cleanup-baseline-verify.log`、`target/cleanup-final-verify.log`，不会将凭据或大量原始日志提交进 Git。下表只统计特定资源清理信号，不把故障注入和其他框架提示一并称作清理故障。

| 验证 | 测试结果 | Hikari `Failed to validate connection` | MySQL `Communications link failure` |
| --- | --- | --- | --- |
| 修改前完整 verify | 823 单元 + 442 集成通过；26 分 40 秒 | 140 | 114 |
| 新增生命周期单元专项 | 3 项通过 | 0 | 0 |
| 修复后真实 MySQL/Redis 专项 | 22 项通过 | 0 | 0 |
| 修复后完整 verify | 826 单元 + 442 集成通过；零失败/错误/跳过；24 分 04 秒，BUILD SUCCESS | 0 | 0 |

本轮基线与修复后回归均未重现此前偶发的 forked VM 退出超时；不能把“这次未重现”写成历史上从未发生。最终日志中 pool shutdown failure、container stop failure、owned resource termination failure、apparent connection leak、forked VM exit timeout 等受检资源信号均为 0。受检连接池、执行器、HTTP 和 Redis 生命周期断言全部通过；这不是对 JVM 全部系统线程的断言。

前端 `npm ci`、类型检查、646 项单元测试、build、38 项 Mock 浏览器测试均通过；60 项交付/部署/自动化契约通过。npm 完整审计保留 5 项 moderate 开发依赖告警，生产依赖审计为 0，逐项说明与 bundle 核查见[依赖风险记录](dependency-risk.md)。测试辅助类未进入生产可执行 JAR。最终远端 CI 以本次提交对应的 GitHub Actions 运行记录为准，五个 job 均成功后才算完成交付。
