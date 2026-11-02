# Redis 共享登录会话

第一阶段只将浏览器登录会话迁入 Redis。订单、聊天记录、知识发布、人工接待和 Agent 检查点继续保存在 MySQL；请求配额、模型并发与熔断暂时仍是每实例状态。

## 本地启动

在项目根目录使用 PowerShell 7：

```powershell
pwsh -NoProfile -File .\scripts\setup-redis.ps1
```

脚本启动独立的 `supportflow-local-redis` 容器，固定使用 `redis:8.2.11-alpine`，只发布 `127.0.0.1:16379`。它生成随机密码，保存到 Git 已忽略的 `.local/redis/` 和 `.local/redis-secrets.yml`；密码通过只读配置挂载交给 Redis，不放入命令参数、Compose 环境变量或输出。Redis 数据保存在 `supportflow-local-redis-data` 卷中，开启 AOF，内存达到上限时拒绝新增写入，避免静默淘汰登录会话。

版本依据 [Redis 8.2.11 官方发布记录](https://github.com/redis/redis/releases/tag/8.2.11) 固定；8.2 属于 [Redis Extended 版本系列](https://redis.io/docs/latest/operate/oss_and_stack/install/version-mgmt/)。

`.local/redis-secrets.yml` 包含应用开关、连接地址、端口、随机密码和命名空间。应用配置导入该文件后，重启后端以启用共享会话。保留该文件即可重复运行脚本；脚本会验证自身管理的容器、卷、网络和配置，不替换其他项目的资源，不重置密码、不清空会话或数据。

仅检查已有实例：

```powershell
pwsh -NoProfile -File .\scripts\setup-redis.ps1 -Check
```

检查会执行带认证的 `PING`，也确认未认证命令被拒绝。输出只包含端口、容器名、镜像和私密配置文件路径。脚本没有删除操作；若发现端口被占用、资源不属于当前工作区、私密配置不完整或被手工改动，会停止并保留现状。

## 数据和有效期

应用开关为 `supportflow.security.session.redis.enabled`，默认关闭；本地生成文件中的 `SUPPORTFLOW_REDIS_SESSION_ENABLED` 会显式开启。连接使用 `spring.data.redis.*`，命名空间使用 `supportflow.security.session.redis.namespace`。本地 Cookie 继续叫 `JSESSIONID`，LAB 继续叫 `SF_LAB_SESSION`。首次切换存储后需重新登录，原聊天和订单数据不受影响。

Spring Session 使用默认 Java 序列化保存认证后的身份；密码与凭据先清空，测试同时核对 Redis 原始属性中没有密码或密码哈希。它保存的是服务端会话，浏览器 Cookie 只有会话标识。[Spring Session 配置说明](https://docs.spring.io/spring-session/reference/configuration/redis.html)

登录后，浏览器继续持有会话标识 Cookie；不同后端实例通过同一个 Redis 命名空间恢复身份及 CSRF 状态。订单归属、账号停用、密码变更版本和店铺授权仍由 Java 与 MySQL 检查。认证后的密码不得作为会话属性保存。

本地和实验环境应使用不同命名空间和原有 Cookie 名，避免互相覆盖。会话 TTL 与应用现有登录有效期保持一致。Redis 不可用时，共享登录功能应返回明确的暂不可用响应，不自动建立单机替代登录。

接入验收必须包含：A 实例登录后由 B 实例读取身份与执行业务请求，B 退出后 A 拒绝旧会话；同时检查 CSRF、到期、账号撤销和 Redis 故障。共享登录并不代表已经完成全局限流、分布式模型并发或完整的多实例部署。

## 本次验证

- 真 HTTP 双实例与一次性 MySQL/Redis：4 项通过，覆盖共享登录、CSRF、退出、TTL、密码清除、账号撤销、Cookie 和 Redis 中断。
- 原单机会话：12 项通过；认证、配置、限流及 Redis 故障处理单元：36 项通过。
- 部署模板及原会话客户端：14 项通过。
- 本地初始化、重复执行、认证 PING、匿名拒绝、loopback 端口和私密配置检查均通过。

双实例测试在同一个 JVM 中创建独立应用实例与 HTTP 端口，共享同一个隔离 Redis；它证明共享会话协议，不代表已经部署独立 JVM 集群、Redis 高可用或全局限流。

## 部署范围

LAB 的应用配置已预留 Redis 地址、密钥和独立命名空间，但默认关闭且本次没有添加 LAB Redis 服务。以后手动启用时，须通过配置树提供 `lab_redis_password`，设置 `SUPPORTFLOW_REDIS_SESSION_ENABLED=true`，并设置 `SUPPORTFLOW_READINESS_COMPONENTS=readinessState,db,redis`，让 readiness 包含 Redis 状态。不要让 LAB 连接本地业务 Redis。

`deploy/compose.redis-local.yml` 是本地开发 Redis 的独立 Compose 配置，不会更改原 LAB 或交付包的服务拓扑。它不能直接叠加到 LAB：LAB 的后端在容器内，需要独立的内部服务地址、命名空间和密钥挂载，不能连接本地开发实例。正式的 LAB Redis overlay 与交付包支持需要单独验证；本次先保留原来的无 Redis 部署路径。
