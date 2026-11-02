# 客服回复流式显示

用户发送消息后，界面先显示准备回复的状态；收到回答片段后，在同一个客服气泡中逐段显示。完成帧校验通过、文字显示完毕后，才恢复正式消息及订单卡片、退款表单等交互。人工客服消息仍使用原有接口和轮询。

## 当前实现的范围

当前 DeepSeek 用于结构化意图识别、工具规划和公开知识证据选择。Java 仍先执行原有归属检查、业务规则、Agent 预算与工具调用，并将完整回复及审计快照原子保存，然后通过 HTTP SSE 分段传输已确认的回答。

这不等于模型生成时的首 token 直出：前面的检索、规划和工具执行耗时仍然存在。系统不会流出模型推理、未校验的工具参数或尚未确认的业务结论。前端按动画帧平滑显示收到的文字；收到最终结果后，显示队列最多再等待 1.5 秒，标签页隐藏时直接完成显示，避免后台暂停动画帧导致界面一直忙碌。服务端没有为显示效果加入延时。知识来源仅保留在最终结构化快照中用于审计，用户界面只展示回答。

## 请求和帧

同一个接口继续支持 JSON；前端选择流式显示时发送：

```http
POST /api/v1/shops/{shopId}/chat/conversations/{conversationId}/messages
Accept: text/event-stream, application/json;q=0.9
Content-Type: application/json
X-CSRF-TOKEN: <当前会话的 CSRF token>

{"clientMessageId":"<UUID>","message":"这件商品发货了吗？"}
```

消息编号、店铺和会话归属校验、身份、CSRF、限流及人工接待互斥都复用原流程。处理失败且尚未开始传输时，仍返回原 HTTP 状态码和 JSON 错误。

正常回答使用 UTF-8 编码，每帧以空行结束：

```text
event: delta
data: {"text":"订单已发货。"}

event: delta
data: {"text":"你可以查看物流进度。"}

event: completed
data: {"conversation": {...}, "messages": [...]}

```

`completed` 的内容是原 `ConversationTurnResponse`。所有 `delta.text` 拼接后必须精确等于最终客服消息正文。解析器处理 UTF-8 跨网络分片、CRLF、多行 data，并限制累计字节、事件数和文本长度；未知事件、重复完成帧、缺失完成帧或最终内容不匹配都视为失败。分片本身只更新临时文字，不执行订单或售后操作。

后端响应设置 `Cache-Control: no-store` 和 `X-Accel-Buffering: no`。部署的 Nginx 等待上游响应的超时设为 110 秒，覆盖 Agent 最多 100 秒的处理预算；浏览器原有 105 秒消息请求期限保持不变。前端使用带会话 Cookie 和 CSRF 的 `fetch` POST 读取响应流，不使用会自动重连的 `EventSource`。协议格式依据 [HTML SSE 标准](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation)。

## 中断与恢复

- 点击“停止输出”会终止本次浏览器请求和文字显示，清除未完成气泡。它不会撤销已保存的回复或已完成的业务读取。
- 切店、换号、退出和离开页面会取消旧请求；旧分片与旧完成帧不能写入新会话。
- 断线、超时或数据不完整时，界面提示读取咨询记录核实。系统不自动重发消息 POST，也不因 SSE 失败再发送一次 JSON 请求。
- 为兼容尚未升级的后端，同一次请求返回合法 JSON 时可以显示该结果；这条兼容路径不代表服务端已经提供 SSE。
- 重复的 `clientMessageId` 仍走原来的持久化幂等结果，不重新执行规划或工具。

本次没有数据库迁移、额外授权、WebSocket 或新模型密钥配置。部署时需要一并更新前后端；本地 Vite 可热更新，后端需要运行包含新接口的构建。

## 验证重点

测试覆盖 SSE 内容协商与真实分段输出、Unicode/JSON 转义、最终快照与幂等、错误和权限边界；前端覆盖完成前可见的文字、完成后才显示交互、截断不重发、取消和跨店隔离。浏览器使用隔离的模拟服务或测试 MySQL，不调用付费模型。
