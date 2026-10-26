# AgentEval workspace

React / TypeScript 管理界面。使用同源 Session Cookie 和 CSRF，不保存密码或 Worker 密钥到浏览器存储。

## 本地开发

使用 Node.js 22.23.4。在本目录运行：

```sh
npm ci
npm run dev -- --port 3000
```

默认代理 `/api` 到 `http://localhost:8080`；通过 `API_TARGET` 指定后端。后端启动见[说明](../docs/development/backend.md)。本地 HTTP 开发需设置后端 `SECURE_COOKIES=false`，正式部署使用 HTTPS 和安全 Cookie。

管理员创建的用户首次登录必须改密。任务集、评测器和评分套件从草稿发布成固定版本。归档保留历史引用。Agent 页面创建的密钥只显示一次，关闭后只能撤销和重新创建。

额度为累计整数 credits，浏览器使用十进制字符串和 BigInt。LLM 草稿可绑定平台模型并查看单次预留估算；试运行使用已结束任务的 Trial ID，独立于正式评分，也会计费。Python 编辑器不会在浏览器执行代码，平台未配置合格强化 Runner 时保持不可执行。

## 检查

```sh
node ../scripts/build-openapi.mjs
npm run generate:api
npm run format:check
npm test -- --run
npm run build
```

API 类型由仓库契约生成。变更接口时先修改 `scripts/build-openapi.mjs`，再生成契约和类型，CI 检查漂移。

真实浏览器和双 SDK 集成使用临时数据库/API/Worker，在仓库根目录完成 `mvn verify` 后执行：

```sh
cd web
npx playwright install chromium
cd ..
python scripts/verify_phase_b.py --web-smoke
```

Python 环境需已安装 SDK 测试依赖；需要 Docker 和 JDK21。脚本创建独立账号并传递临时环境变量，结束后只清理自身服务。浏览器不录制 trace/video；截图只包含普通任务集页面，不保存一次性密钥。
