# 前端依赖风险记录

审计日期：2026-11-03；基线提交：`94e8ff0`；Node 22.22.2。本轮未修改 `package.json` 或锁文件，未运行强制修复、降级、major 升级或 overrides。

在按原锁文件执行 `npm ci`、类型检查、646 项单元测试、生产构建和 38 项 Mock 浏览器测试后，再次运行 `npm audit --json` 及 `npm audit --omit=dev --json`。完整审计为 **5 项 moderate、0 high、0 critical**；生产依赖审计为 **0**。[归档 JSON](evaluations/dependency-audit-2026-11-03.json)包含实际公告、依赖树、上游 manifests 和成品检查摘要。

## 五项告警的来源

5 是被 npm 标记的包数量，不是 5 个独立漏洞：当前两条上游公告传播到三个父包，形成五项告警。入口均为 `devDependencies` 中直接锁定的 `@vue/test-utils@2.5.1`。

```text
@vue/test-utils 2.5.1 (direct dev)
└─ js-beautify 2.0.3
   ├─ editorconfig 3.0.2
   │  └─ minimatch 10.2.6 (deduped)
   └─ glob 13.0.6
      └─ minimatch 10.2.6
```

| Package / 当前版本 | Direct / transitive | Severity / 类型 | Scope | 上游修复版本与兼容性 | Production reachable? | 本轮处理 |
| --- | --- | --- | --- | --- | --- | --- |
| `@vue/test-utils 2.5.1` | direct | moderate；继承 formatter 子依赖告警，没有独立公告 | dev / unit test | 当前 latest 仍为 2.5.1，无已发布的父包修复版本 | 未进入成品模块图 | 保留 |
| `js-beautify 2.0.3` | transitive | moderate；继承 glob 与 editorconfig 告警 | dev / unit test | 当前 latest 仍为 2.0.3；要求 glob `^13.0.6`、editorconfig `^3.0.2`，不能直接解析到已修复 glob 14 | 未进入成品模块图 | 保留 |
| `editorconfig 3.0.2` | transitive | moderate；继承 minimatch 的模式展开 DoS | dev / unit test | 当前 latest 仍为 3.0.2；要求 minimatch `~10.2.4`，不接受修复版本 11.0.1+ | 未进入成品模块图 | 保留 |
| `glob 13.0.6` | transitive | moderate；AbortSignal 取消后仍遍历目录，造成额外 I/O/CPU；同时继承 minimatch 告警 | dev / unit test | 此公告修复于 **14.0.2**，当前 latest 14.0.3；超出父包 `^13.0.6`，需要跨 major | 未进入成品模块图 | 保留 |
| `minimatch 10.2.6` | transitive | moderate；攻击者模式展开被放大，增加 CPU/内存消耗 | dev / unit test | 修复于 **11.0.1**，当前 latest 11.0.4；超出父包 10.x 范围，需要跨 major | 未进入成品模块图 | 保留 |

原始公告：[glob GHSA-3g3m-2fjr-6pw4](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4)、[minimatch GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249)。这是可用性风险，不是已证明的远程代码执行。公告涉及受攻击者影响的 glob 模式或目录遍历；不能由生产依赖为 0 推导出开发/CI 环境没有风险。

## 为什么本轮不强行清零

本轮能核实的两个修复版本分别跨过 glob 和 minimatch 当前父依赖的 major 边界，未发现可在原范围内正常解析的修复补丁。`npm audit` 建议将顶层工具回退至 `@vue/test-utils 2.4.11`，并把这项修复标记为 `isSemVerMajor=true`。版本号本身是回退而非 major 升级；该字段不能证明实际 API 一定不兼容，也不能代替对回退后完整依赖树的验收。

当前顶层工具已是已发布的 latest。为了隐藏审计数字而回退测试工具或强制替换 glob/minimatch major，不能证明收益大于测试兼容与可复现风险。故本轮**升级项为 0，五个包均保留**，没有删除测试依赖、放宽 TypeScript 或关闭告警。

Review condition：`@vue/test-utils` 或 `js-beautify`/`editorconfig` 发布能正常引用已修复子依赖的兼容版本；公告增加适用于当前 major 的补丁；或真实使用路径、严重性、生产导入发生变化。届时更新锁文件并完整重跑类型检查、单元、build、浏览器与审计，不能只看 audit 数量。

## Production bundle 的实际检查

`frontend/package.json` 的生产依赖只有 `vue` 与 `@lucide/vue`。成品核查还做了以下验证，避免只依赖依赖分类：

1. 使用同一 Vite production 配置获取最终 chunk 模块图，共 93 个模块；涉及的 npm 包仅 Vue runtime/reactivity/shared 与 `@lucide/vue`。
2. 第二次只读构建的 JS、CSS、HTML 最终输出与实际 `frontend/dist` 的 3 个生成文件逐字节 SHA-256 一致。是在所有构建钩子完成后比较，不使用中间态 CSS 判断成品。
3. 检查全部 8 个 dist 文件，未发现 Playwright、Vitest、jsdom、test-utils、formatter 链或 e2e/test fixture 源模块；未发现 `.test.*`、TypeScript 源码、source map、已知测试密码标记及进程环境中的实际密钥值。

这覆盖当前已知测试标记与本次模块/文件清单，不宣称是通用秘密检测器。`products/demo` 中的四张图片是项目正式引用的演示商品静态资源，随 UI 分发；它们不是 e2e 测试夹具。backend Java 运行时也不使用这条 npm 测试依赖链。

成品模块包清单、文件大小和 SHA-256 已在上述归档 JSON 中保存。临时构建探针与原始命令日志保留在 Git 忽略的 `target/cleanup-*` 中；未更改业务源码或 Vite 的生产配置。
