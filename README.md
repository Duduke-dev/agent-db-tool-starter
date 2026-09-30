# agent-db-tool-starter

让 LLM agent 能查数据库，但**只能碰你明确授权的表和字段**。

## 它解决什么

给模型一个 `execute_sql(String)` 看起来最简单，但白名单会立刻失效：它能把未授权的表 JOIN 进来、用
`SELECT *` 捞出敏感列、拼接注入。事后加过滤也靠不住——过滤逻辑写错一次就全漏。

这个 starter 换了个思路：**模型没有生成 SQL 的能力**。

- 它只能传结构化的查询参数（表名、列名、操作符、值），SQL 由服务端在授权范围内编译
- 表名和列名只从你的授权表里取，而且是数据库元数据里的真实对象，不以字符串形式参与拼接
- 授权是数据不是代码——改授权表，下一次调用立即生效，不用重启

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | 25 | |
| Spring Boot | 4.1.1 | |
| Spring AI | 2.0.1 | 只用 `spring-ai-model` 里的 `@Tool` / `ToolCallbackProvider`，**不引入任何模型 starter** —— 所以这个库不需要 API key，测试也不经过模型 |
| jOOQ | 3.21.7 | 版本交给 Boot 管理；所有 SQL 都由它按方言生成 |
| HikariCP | 随 Boot | 连接池，只读设置在这一层 |
| Maven | 3.9+ | 单模块，独立构建 |

**为什么是 jOOQ**：这个项目的核心约束是"标识符不能以字符串形式参与拼接"。jOOQ 用
`Table` / `Field` 对象表示标识符，正好对上——加上它按方言渲染 SQL，换库时不用改代码。
MyBatis 靠 XML 拼字符串、JdbcTemplate 更接近手写 SQL，都达不到这个强度。

**为什么不用 Spring Data JPA**：JPA 的查询能力绑定在实体模型上，而这个工具要的是
"任意授权表 + 任意授权列 + 结构化条件"，没有实体概念，反而要绕开 ORM 的抽象。

## 快速接入

### 1. 加依赖

```xml
<dependency>
  <groupId>com.duduke</groupId>
  <artifactId>agent-db-tool-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

这个 starter 刻意**不带数据库驱动、不带 Web**：用什么数据库、对外暴露什么形态，都由你决定。

```xml
<!-- 你需要自己提供其中之一 -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

### 2. 准备数据库账号

**应用使用的账号必须是只读的。** 这是唯一一道"应用层有 bug 也绕不过去"的防线。

```sql
-- PostgreSQL
CREATE ROLE agent_ro LOGIN PASSWORD '<改掉>';
GRANT CONNECT ON DATABASE yourdb TO agent_ro;
GRANT USAGE   ON SCHEMA public  TO agent_ro;
GRANT SELECT  ON ALL TABLES IN SCHEMA public TO agent_ro;

-- 必须带 FOR ROLE：不带的话只对「执行者自己创建的表」生效，
-- 而建表的是另一个角色，等于白写。这个坑的现象很迷惑：
-- 授权表能读，但新建的业务表读不了。
ALTER DEFAULT PRIVILEGES FOR ROLE <建表角色> IN SCHEMA public
  GRANT SELECT ON TABLES TO agent_ro;

ALTER ROLE agent_ro SET default_transaction_read_only = on;
ALTER ROLE agent_ro SET statement_timeout = '5s';
```

### 3. 配置

```yaml
spring:
  datasource:
    url: "jdbc:postgresql://localhost:5432/yourdb"
    driver-class-name: org.postgresql.Driver
    username: agent_ro
    password: <只读账号密码>
    hikari:
      read-only: true          # 用 JDBC 的 setReadOnly，换库时这行不用改

agent-db:
  # 按需装载哪几组工具 —— 关掉一组，对应的 bean 根本不注册，模型也看不到它们的工具说明。
  # 工具说明是要占上下文的；给模型一堆用不到的工具，既浪费 token 又降低选择准确率。
  tools:
    query: true        # 自由查询：list_tables / describe_table / query（默认开）
    template: false    # 查询模板：list_query_templates / run_query_template（默认关）

  # 启动时建授权表并把库里现有的表和字段全部登记为清单。
  # 用管理员连接 —— 应用账号是只读的、建不了表。
  bootstrap:
    enabled: true
    username: <有 DDL 权限的账号>
    password: "${AGENT_DB_BOOTSTRAP_PASSWORD}"

  # 所有限制都有代码内默认值（见 AgentDbProperties.Limits），需要覆盖时才写
  # limits:
  #   max-limit: 500
  #   statement-timeout: 10s
```

**关掉一组意味着什么**：不是"工具不暴露给模型"，而是**整组 bean 都不注册**——
仓储、服务、建表全都不跑。所以关掉 `template` 时不会去建 `agent_query_template` 表。

`query` 默认开（关掉它这个 starter 就没有任何工具了）；`template` 默认关
（它是可选能力，且要多建一张表）。

### 4. 挂到 ChatClient

工具集以两个 `ToolCallbackProvider` bean 暴露：

| bean 名 | 包含的工具 | 开关 |
|---|---|---|
| `agentDatabaseToolCallbackProvider` | `list_tables` / `describe_table` / `query` | `agent-db.tools.query`（默认 true） |
| `agentQueryTemplateToolCallbackProvider` | `list_query_templates` / `run_query_template` | `agent-db.tools.template`（默认 false） |

**刻意分成两个**：你可以在不同场景只挂需要的那组，比如做探索式问答时只挂自由查询，
做固定报表时只挂模板。

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                      ToolCallbackProvider agentDatabaseToolCallbackProvider,
                      ToolCallbackProvider agentQueryTemplateToolCallbackProvider) {
    return builder
            .defaultToolCallbacks(agentDatabaseToolCallbackProvider,
                                  agentQueryTemplateToolCallbackProvider)
            .build();
}
```

只开自由查询时，第二个 provider 不存在，直接注入会启动失败——按上面的开关配好即可。

## 五个工具

模型看到的全部能力，到此为止：

### 自由查询（`DatabaseAgentTools`）

| 工具 | 作用 |
|---|---|
| `list_tables()` | 有哪些可查的表（表名 + 说明 + 可访问列数） |
| `describe_table(List<String>)` | 一张或多张表的列结构、类型、四个能力位 |
| `query(List<QueryRequest>)` | 查询，支持选列 / 过滤 / 聚合 / 分组 / 排序 / 分页 |

`query` 的签名里没有任何地方能塞 SQL 片段：

```java
query([{
  "table": "product",
  "select": [{"column": "category"}, {"column": "id", "aggregate": "COUNT", "alias": "cnt"}],
  "where":  [{"column": "price", "operator": "GT", "values": ["100"]}],
  "groupBy": ["category"],
  "orderBy": [{"column": "category", "direction": "ASC"}],
  "limit": 20
}])
```

支持批量提交，**每条独立返回结果或错误**——某条失败不影响其他条，且会带着错误码回来，
模型看错误码就知道该改什么。

### 查询模板（`QueryTemplateTools`）

| 工具 | 作用 |
|---|---|
| `list_query_templates()` | 有哪些预定义查询模板、每个要传什么参数 |
| `run_query_template(name, params)` | 按模板名 + 参数执行 |

**模板是"让模型挑选已固化的查询"，和"让模型自由表达查询"是两件事**，所以分在两组工具里。

它的价值在于：常用统计（比如"按月统计各分类销售额"）如果让模型每次现拼聚合查询，
既费往返又容易算错口径。把查询固化成模板后，模型只需选模板 + 填参数，
**2 轮完成，口径统一**。

模板存在 `agent_query_template` 表里，由 DBA 登记：

```jsonc
{
  "template_name": "monthly_sales_by_category",
  "description": "按月统计各分类销售额",
  "visible": true,
  "template_json": {
    "table": "product",
    "select": [{"column":"category"}, {"column":"price","aggregate":"SUM","alias":"total"}],
    "where": [
      {"column":"created_at","operator":"GE","values":["${monthStart}"]},
      {"column":"created_at","operator":"LT","values":["${monthEnd}"]}
    ],
    "groupBy": ["category"],
    "orderBy": [{"column":"category","direction":"ASC"}]
  },
  "parameters_json": [
    {"name":"monthStart","type":"DATE","description":"统计起始日（含）","required":true},
    {"name":"monthEnd","type":"DATE","description":"统计结束日（不含）","required":true}
  ]
}
```

模型调用：

```jsonc
run_query_template("monthly_sales_by_category",
                   {"monthStart": "2026-09-01", "monthEnd": "2026-10-01"})
```

#### 模板的安全边界（重要）

**`${param}` 只能出现在 `where[].values[]` 里**，绝不能用于 `table` / `column` /
`operator` / `groupBy` / `orderBy` / `limit`。

一旦参数能充当标识符，模型就绕开了授权表、能自己决定查哪张表和哪个列——**授权就白做了**。
所以模板在加载时会做白名单式的位置校验，不合法的模板直接拒绝执行。

其他几条：

- **模板不产生任何新权限**：填充后产出的就是标准 `QueryRequest`，走和 `query` 完全相同的链路。
  模板引用的列如果被取消授权，执行时一样会被拒绝
- **拒绝未声明的参数**（而不是静默忽略）：模型传 `table` 这类名字说明它在试探
- 参数支持 `STRING` / `NUMBER` / `DATE` / `DATETIME` / `BOOLEAN` 五种类型，
  可声明必填和枚举取值（`allowedValues`）
- 参数声明与用法会做交叉检查：「声明了但没用到」「用了但没声明」都会报错
- 模板默认 `visible = FALSE`，和授权表一致的「默认拒绝」

**写模板的权限等同 DBA 权限**——因为能定义模板就等于能定义"agent 能查什么"。

## 授权模型

**授权**用这两张表，`bootstrap` 开启时自动创建（查询模板另有一张
`agent_query_template`，见「查询模板」一节）：

```sql
agent_table_policy  (table_name PK, description, visible, queryable)
agent_column_policy (table_name, column_name, description, accessible,
                     selectable, filterable, sortable, groupable,
                     PRIMARY KEY (table_name, column_name))
```

**默认拒绝**：不在这两张表里的表和列，agent 一律不可见。

### 扫描登记的是"清单"，不是"授权"

首次启动会把库里所有表和字段扫进来，但一律是**关闭**状态
（表 `visible = FALSE`、字段 `accessible = FALSE`）。要放开哪个手工改标记：

```sql
UPDATE agent_table_policy  SET visible = TRUE WHERE table_name = 'product';
UPDATE agent_column_policy SET accessible = TRUE WHERE table_name = 'product' AND column_name = 'sku';
```

否则一启动就等于把整库开放了。

写入用 `ON CONFLICT DO NOTHING`，所以**重复启动不会覆盖你已经调好的授权**，
只会补录新增的表和字段。

### 四个能力位

| 位 | 管什么 | 对应 SQL |
|---|---|---|
| `accessible` | 总开关 | 不参与 SQL |
| `selectable` | 能否读出来 | `select` |
| `filterable` | 能否当条件 | `where` |
| `sortable` | 能否排序 | `orderBy` |
| `groupable` | 能否分组 | `groupBy` |

拆开而不是一个开关，是因为**同一个字段的不同用法风险不同**。比如自增主键：可以返回、可以排序，
但不给 `where id = ?` 的能力——那等于允许逐条探测整张表。

## 安全模型

**三层只读**，硬度递增：

1. **DSL 层**——查询请求里根本没有写操作，"写"这件事不存在
2. **连接层**——Hikari 的 `setReadOnly(true)`，会话级设置
3. **账号层**——`GRANT SELECT` + `default_transaction_read_only`，**应用改不动**

只有第 3 层是硬的。前两层本质上是"自己约束自己"，别因为麻烦就省掉账号层的配置。

**其他几条**：

- 表/列不存在与未授权，对外都报 `TABLE_NOT_VISIBLE` / `COLUMN_NOT_VISIBLE`——不区分，
  否则 agent 能靠错误码差异把库里的表名枚举出来
- 查询有行数上限、字节上限、超时；`limit` 超上限**拒绝**而不是静默截断
  （静默截断会让模型以为拿到了全量数据）
- 返回给模型的结果里不含编译后的 SQL，SQL 只进日志

## 不支持的

刻意的能力边界，不是缺失：

- **不让模型写 SQL**，也没有"自然语言转 SQL"——那是模型和上层的事
- **不支持写操作**：没有 INSERT / UPDATE / DELETE
- **不支持 JOIN / 子查询 / UNION**：跨表分析靠提前建只读视图，再把视图当普通表登记
- **不内置业务权限模型**：不知道用户、角色、租户是什么，也不知道"行级权限"这种概念

## 项目结构

```
com.duduke.agentdb
├── AgentDbToolAutoConfiguration          自动配置入口（@Import 注册全部组件 + ToolCallbackProvider）
│
├── config/         应用级参数
│   └── AgentDbProperties                 limits / bootstrap
│
├── error/          错误码与异常
│   ├── ToolErrorCode                     16 个错误码
│   └── AgentToolException                带错误码的拒绝异常
│
├── policy/         授权策略：存、读、编译
│   ├── PolicySchema                      两张授权表的 Table / Field 定义（建表与读授权共用）
│   ├── PolicyTableInitializer            启动期：建表 + 扫描登记清单
│   ├── PolicyRepository                  读授权 + 编译成模型
│   └── model/                            编译产物
│       ├── ResolvedTable                 一张已授权的表（含 jOOQ Table 与列映射）
│       ├── ResolvedColumn                一个已授权的列（含 jOOQ Field 与四个能力位）
│       └── TableOverview                 list_tables 用的轻量视图（不读元数据）
│
├── query/          查询的编译与执行
│   ├── QueryCompiler                     三层校验编排 + jOOQ 编译
│   ├── CompiledQuery                     编译产物：可执行的 jOOQ 查询
│   ├── AgentQueryService                 执行 → 截断 → 返回
│   ├── QueryResult                       结果集：列名、行数据、是否被截断
│   ├── dsl/                              模型能表达的查询（没有 JOIN、没有 SQL 字符串）
│   │   ├── QueryRequest                  table / select / where / groupBy / orderBy / limit
│   │   ├── SelectItem / Condition / OrderItem
│   │   └── Operator / Aggregate / SortDirection
│   └── validation/                       三层校验，各管一件事
│       ├── AuthorizationValidator        这个标识符能不能碰
│       ├── ParameterValidator            能不能这么用
│       └── SemanticValidator             这么写 SQL 成不成立
│
├── template/       查询模板（预定义参数化查询）
│   ├── QueryTemplateSchema               模板表定义
│   ├── QueryTemplateInitializer          启动期建模板表
│   ├── QueryTemplateRepository           读模板 + 严格解析
│   ├── QueryTemplateService              对外门面：模板 + 参数 → QueryRequest
│   ├── TemplatePlaceholder               占位符校验与填充 ← 安全边界在这
│   ├── TemplateParameterValidator        参数值校验
│   ├── dto/  QueryTemplateInfo
│   └── model/  ResolvedTemplate / TemplateParameter / ParameterType
│
└── tool/           对模型的暴露面
    ├── DatabaseAgentTools                自由查询的 @Tool 门面
    ├── QueryTemplateTools                模板查询的 @Tool 门面
    └── dto/                              回给模型的数据形状
        ├── TableSummary / TableDescriptor / ColumnDescriptor
        └── QueryOutcome                  批量查询里单条的结果或错误
```

**依赖方向是单向的**：`tool → template → query → policy → error`。
上层依赖下层，没有反向依赖和环。

**主包放行为、子包放数据**是这个项目的惯例：`policy/model`、`query/dsl`、
`tool/dto`、`template/model`。读代码时不用每次重新判断该去哪找。

**`template` 和 `query` 是平行的两套**：前者"挑选已固化的查询"，后者"自由表达查询"。
`template` 唯一的对外出口是 `QueryTemplateService`，它产出标准 `QueryRequest` 后就交回 `query`。
内部的 `TemplatePlaceholder` / `TemplateParameterValidator` 保持包内可见 ——
**只有一处入口可能被绕过**。

## 数据库支持

业务代码（查询表达、校验、SQL 生成）与数据库无关——换库不用改。

但有三类差异消不掉，换库时需要重配：

| 项 | PostgreSQL | MySQL |
|---|---|---|
| 账号级只读 | `ALTER ROLE ... default_transaction_read_only` | **无等价物**，只能 `GRANT SELECT` + 会话级 |
| 查询超时 | `statement_timeout`（可设账号级） | `max_execution_time`（只能会话级，且仅 SELECT） |
| 授权语法 | `CREATE ROLE` + `ALTER DEFAULT PRIVILEGES FOR ROLE` | `CREATE USER 'x'@'%'` + `GRANT ON db.*` |

**"业务代码换库不改"成立；"安全装备也统一"不成立。** 后者是数据库的本质差异，
不是技术债，别指望有库能抹平。

## 已知边界

- **`orderBy` 不支持按聚合别名排序**：`ORDER BY cnt DESC`（`cnt` 是 `select` 里的别名）
  在 SQL 上合法，但校验把 `orderBy.column` 当真实列名查授权表，别名会被判成「列不存在」。
  目前只能用 `groupBy` 里的真实列排序。这是个真实的能力缺口，不是设计选择
- **不支持多 schema**：授权表里的表名不带 schema 前缀，两个 schema 有同名表时会取与当前连接一致的那个
- **布尔类型在 MySQL 上有差异**：MySQL 的 `BOOLEAN` 实为 `TINYINT(1)`，元数据报 `TINYINT`，
  因此模型传 `'true'` 去筛布尔列会失败，得传 `'1'`
- **测试需要连真实数据库**：`mvn test` 需要本地有 PostgreSQL 和相应账号，
  配置模板见 `src/test/resources/application.yml.example`。
  这是这个项目的一个已知弱点 —— 别人 clone 下来跑不了测试
