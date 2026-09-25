# 番茄小说排行榜爬虫（FQXS）

Spring Boot 3.3 + OkHttp 抓取 `fanqienovel.com` 排行榜（男/女频 × 阅读榜/新书榜，37 个子分类），
H2 文件库落库，无框架单页展示与筛选。代码地图、安全模型设计理由、环境陷阱见 [`HANDOVER.md`](HANDOVER.md)。

## 运行

```bash
./mvnw -B spring-boot:run
# 打开 http://localhost:8080
```

不需要任何数据库服务：数据落在 `data/fqxs.mv.db`（已 gitignore），启动自动建表并幂等回填历史数据，备份 = 停应用后拷该文件。

## 配置（环境变量）

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `DB_URL` | JDBC 连接串 | `jdbc:h2:file:./data/fqxs;DB_CLOSE_ON_EXIT=FALSE` |
| `DB_USERNAME` / `DB_PASSWORD` | 库账号 / 口令 | `sa` / 空 |
| `APP_ADMIN_TOKEN` | `/api/db/**` 管理令牌 | 空（未配置一律 403） |

> 历史提交中曾出现明文 SA 口令，仓库公开，请轮换。

## 数据模型

- 建表脚本 `db/schema-h2.sql` 启动幂等执行；应用 `ddl-auto: none` 永不改库。
- 单表 `novel_rank`：`(category_code, book_id)` 组合唯一（一本书可同上多榜）、`gender`、
  `is_blocked`/`blocked_time`（拉黑名单不会被掉榜清理抹掉）、`rank` 为字符串列（查询 `CAST` 排序）。
- 配置表 `app_config`：`rank_type`（read/new）与 `display_categories`（展示哪些分类），
  `ConfigService` 内存缓存 + 写后失效；改配置走 `POST /api/rank/config`，重启仍生效。
- 加列/唯一键迁移/历史数据回填由 `SchemaInitializer` + `StartupDataInitializer` 启动幂等完成。
- 库不可用降级：启动不被阻断（建表/回填失败只记日志），读接口 503，抓取结果不落库并在响应标 `dbSaved=false`。

## 接口

写操作一律 POST 且要求 `X-Rank-Token`（页面启动时 `GET /api/rank/write-token` 按会话领取）；
`/api/db/**` 全方法要 `X-Admin-Token`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/rank/meta` | 页面唯一引导：rankTypes + 全量 37 分类（含 displayed 标记） |
| GET | `/api/rank/write-token` | 领写令牌 |
| POST | `/api/rank/config` | 更新 `rankType` / `displayCategories`（白名单校验），回包与 meta 同形 |
| POST | `/api/rank?category=&size=` | 抓单个分类整榜（`category` 必填传分类 code，`size` 上限 100） |
| POST | `/api/rank/fetchAll` | 逐个**展示中分类**抓整榜并汇总（异步，最长 10 分钟） |
| POST | `/api/rank/block/{bookId}` / `unblock/{bookId}` | 拉黑 / 恢复（作用于该书全部分类行） |
| GET | `/api/rank/allBooks` | 读库筛选：`category` 传 code 逗号分隔（空=全库）、`wordRange`、`status`、`blockedOnly` |
| GET | `/api/rank/categories` `/config` | 兼容保留，排查用 |
| GET/DELETE | `/api/db/all`、`/api/db/category?code=` | 整表 / 按分类读删，要 admin 令牌 |

错误统一 `{code, message}`：502 上游失败、503 库不可用、400 参数不合法、401/403 令牌问题、405 GET 调写接口。
安全头：CSP `default-src 'self'`（`script-src`/`style-src` 收 `'self'`，`font-src` 放行反混淆字体域名），全站 `no-store`。

## 技术要点

- 上游寻址三元组 `(gender, category_id, rankMold)`：gender 1=男频/0=女频，rankMold 2=阅读榜/1=新书榜，
  `rank_list_type=3` 恒定；男女频共用部分 category_id，故用 `M_`/`F_` 前缀的全局唯一 code 区分（`CategoryCatalog` 枚举，37 项经 meta 下发）。
- 每个榜恒 100 条：抓到 0 条一律按失败处理（502）——上游风控的表现就是 200 + 空 `book_list`；整榜抓取才触发掉榜清理（只删未拉黑行）。
- 书名/作者/简介是 PUA 私用区字符，靠被爬站点的反混淆字体（`font-DNMrHsV173Pd4pgy`，from bytetos.com，CSP `font-src` 已放行）显示，否则渲染成 □。
- 前端零框架零内联：ES modules + 事件委托 + `hidden` 显隐，唯一脚本入口 `/js/app.js`（ES module）。
- 时间按北京时间落库与格式化（`DateUtil`），不随宿主机时区。
