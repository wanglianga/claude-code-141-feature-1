# 校园食材供应商订货验收入库与索赔服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的后端服务，覆盖校园食堂食材供应链全流程：

```
周菜单 → 采购计划 → 订货单 → 送货批次 → 验收 → 入库/退货/降级 → 索赔 → 结算 → 影响分析 → 复盘统计
                              └ 冷链温度超标：暂扣 → 食堂处置（拒收/降级非学生餐/等待复检）→ 替代采购 + 菜单调整
```

## 功能总览

| 模块 | 说明 |
|---|---|
| 周菜单 | 食堂按周维护餐次与食材用量，发布后生成采购计划 |
| 采购计划 | 汇总菜单需求，扣除可用库存（保留安全线）得出采购量，按品类匹配供应商 |
| 订货单 | 按计划按供应商自动拆单，记录规格、数量、合同价、质检要求、是否冷链、要求到货时间 |
| 送货批次 | 登记车牌、司机、到货时间、车厢温度；自动识别**晚送 / 冷链温度超标 / 临时替换品类** |
| 验收 | 验收员逐项核对重量、腐烂比例、检疫票、外观、抽检；合格入库、拒收退货、降级待审批；自动识别**少送（按累计送货量）/ 腐烂超标 / 票证缺失**；**食堂已备餐无法拒收**时自动转附条件接收并生成折价索赔 |
| 冷链温度超标处置 | 供应商配送酸奶等冷链食材车厢温度高于合同要求时，验收员录入**到货温度、运输时长、产品批次、抽检照片**，问题食材整批**暂扣**待食堂处置：**拒收**（退货+按拒收货值 20% 自动核准违约金索赔，直接进结算；影响当天菜单时同步生成**替代采购单与菜单调整记录**及家长公告草稿）、**降级为非学生餐使用**（只能进入**教职工餐或非即食加工**，经采购折价审批 + **质检复查双闸门**后入受限库存，领用时强制校验用途）、**等待复检**（质检复测，合格正常入库并免除冷链扣罚，不合格可再拒收/降级） |
| 库存 | 库存水位 + 流水（入库/出库/退货/调整），安全线预警；冷链降级批次为**受限库存**，按用途（学生餐/教职工餐/非即食加工）管制领用 |
| 退货 | 拒收与降级驳回自动生成退货单，跟踪退货完成 |
| 索赔 | 手工登记或系统自动生成（无法拒收折价），审批/支付流转 |
| 结算 | 按实际验收重量×合同价，扣减退货、降级折价、索赔、合同扣罚生成账单；确认时录入供应商开票金额，不一致自动登记**结算差异异常** |
| 影响分析 | 验收失败批次 → 受影响餐次、替代食材（替代组→同品类）、家长公告草稿、供应商责任 |
| 复盘统计 | 按供应商/品类/验收员统计异常与拒收率；按归因区分**天气质量波动 / 供应商履约不稳 / 验收标准执行偏差** |

## 异常类型与合同扣罚规则

| 异常 | 触发时机 | 扣罚 |
|---|---|---|
| 晚送 LATE_DELIVERY | 送货登记（宽限 30 分钟） | 订单金额 5%，最低 200 |
| 冷链超标 COLD_CHAIN_BREACH | 送货登记（车厢温度超出食材冷链区间）/ 验收暂扣 | 冷链货值 5%，最低 300；验收暂扣案件**复检合格可免除** |
| 替换品类 SUBSTITUTION | 送货登记（实送食材≠订购食材或计划外送货） | 送货金额 10% |
| 少送 SHORT_DELIVERY | 验收（按订货明细累计送货量判定） | 少送金额 10% |
| 腐烂超标 HIGH_ROT_RATIO | 验收（蔬果阈值 10%，其他 5%） | 腐烂金额 5% |
| 票证缺失 MISSING_DOCUMENTS | 验收（肉类无检疫票） | 固定 100 |
| 已备餐无法拒收 CANNOT_REJECT_PREPARED | 验收（拒收食材已被备餐餐次需要） | 不扣罚，自动生成货值 50% 折价索赔 |
| 结算差异 SETTLEMENT_MISMATCH | 结算确认（开票金额≠系统应付） | 不扣罚，仅标记 |

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html （OpenAPI 3.x 文档：/v3/api-docs）
- PostgreSQL：localhost:5432（food / food123 / fooddb），数据持久化在 `pgdata` volume

首次启动自动初始化演示数据（4 供应商、12 食材、库存、最近一个工作周菜单）。设 `APP_SEED_DEMO_DATA=false` 可关闭（环境变量对应 `app.seed-demo-data`）。

## 本地开发启动

前置：JDK 17、Maven 3.8+、PostgreSQL（创建 `fooddb` 库与 `food/food123` 用户，或用下方环境变量指向已有实例）。

```bash
# 1. 准备数据库（示例：本地 5432，或任意实例）
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/fooddb
export SPRING_DATASOURCE_USERNAME=food
export SPRING_DATASOURCE_PASSWORD=food123

# 2. 启动
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/food-procurement-service-1.0.0.jar
```

## 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 | 主要权限 |
|---|---|---|---|
| inspector | inspector123 | 验收员 | 创建验收记录、录入冷链到货温度/运输时长/产品批次/抽检照片 |
| buyer | buyer123 | 采购负责人 | 供应商/食材/下单/送货登记/降级审批/退货/索赔/结算/异常处理 |
| canteen | canteen123 | 食堂 | 周菜单/生成采购计划/库存领用/冷链案件处置（拒收/降级/复检）/菜单调整确认 |
| quality | quality123 | 质检人员 | 冷链复检、降级批次入库前复查 |
| admin | admin123 | 校方 | 公告发布、复盘统计、全部只读 |

## 验证方式

### 1. 单元测试

```bash
mvn test
```

### 2. 端到端冒烟脚本（推荐）

应用启动后执行（脚本需**全新数据库**，重置后重启应用再跑）：

```bash
python3 scripts/smoke.py http://localhost:8080
```

脚本以 5 个角色走完整流程并断言 83 项检查：权限拦截 → 菜单/计划/拆单 → 送货（晚送+冷链+替换）→ 验收（少送/腐烂/缺票证/降级）→ 库存与安全线 → 降级审批/退货 → 影响分析与家长公告 → 已备餐无法拒收（附条件接收+自动索赔）→ 索赔 → 结算（金额逐项断言+对账差异）→ **冷链温度超标专项（暂扣取证 → 拒收联动退货/自动索赔/替代采购/菜单调整/公告 → 降级双闸门与受限库存用途管制 → 复检合格放行免扣罚）** → 供应商看板/异常归因/复盘统计 → 未认证拦截与 OpenAPI 文档。

### 3. 冷链温度超标专项手工体验（curl 示例）

```bash
# 1) 验收员登记冷链超温批次：录入到货温度/运输时长/产品批次（随送货明细）/抽检照片，整批暂扣
curl -u inspector:inspector123 -X POST http://localhost:8080/api/acceptances \
  -H 'Content-Type: application/json' -d '{
    "batchId": 1, "measuredTemp": 12, "temperatureOk": false, "appearanceOk": true,
    "samplingResult": "酸奶车厢温度超标", "transportDurationMinutes": 210,
    "photosUrl": "http://img/a.jpg,http://img/b.jpg",
    "items": [{"deliveryItemId": 1, "coldChainHeld": true}]}'
# 2) 食堂查询待处置案件并三选一：REJECT 拒收 / DOWNGRADE 降级（须带 usageScope）/ RETEST 等待复检
curl -u canteen:canteen123 'http://localhost:8080/api/cold-chain-cases?status=ON_HOLD'
curl -u canteen:canteen123 -X POST http://localhost:8080/api/cold-chain-cases/1/decide \
  -H 'Content-Type: application/json' -d '{"disposition":"DOWNGRADE","usageScope":"STAFF_MEAL"}'
# 3a) 拒收路径：自动退货 + 货值20%已核准索赔 + 替代采购单 + 菜单调整（GET 案件详情可见）
# 3b) 降级路径：采购批准折价 → 质检复查通过 → 限教职工餐/非即食加工的受限库存
curl -u buyer:buyer123   -X POST http://localhost:8080/api/cold-chain-cases/1/downgrade-approval \
  -H 'Content-Type: application/json' -d '{"approve":true,"discountRate":0.2}'
curl -u quality:quality123 -X POST http://localhost:8080/api/cold-chain-cases/1/quality-review \
  -H 'Content-Type: application/json' -d '{"passed":true,"note":"复查合格"}'
# 3c) 复检路径：质检录入复测结论，合格正常入库并免除冷链扣罚
curl -u quality:quality123 -X POST http://localhost:8080/api/cold-chain-cases/1/retest \
  -H 'Content-Type: application/json' -d '{"outcome":"PASSED","retestTemp":5}'
# 4) 受限库存用途管制：学生餐禁止使用降级批次（超量返回 400）
curl -u canteen:canteen123 -X POST http://localhost:8080/api/inventory/consume \
  -H 'Content-Type: application/json' -d '{"ingredientId":10,"qty":10,"purpose":"STUDENT_MEAL"}'
```

### 4. 手工体验（既有流程 curl 示例）

```bash
# 生成采购计划（菜单 id 见 GET /api/menus）
curl -u canteen:canteen123 -X POST http://localhost:8080/api/plans/menus/1/generate -H 'Content-Type: application/json' -d '{}'
# 下单（计划 id 见上一步）
curl -u buyer:buyer123 -X POST http://localhost:8080/api/plans/1/orders -H 'Content-Type: application/json' \
  -d '{"expectedArrivalAt":"2026-10-06T08:00:00"}'
# 供应商异常看板
curl -u buyer:buyer123 http://localhost:8080/api/anomalies/dashboard/2
# 复盘统计
curl -u admin:admin123 http://localhost:8080/api/stats/by-supplier
```

全部接口见 Swagger UI。

## 实际验证结论（本环境已执行）

- `mvn test`：**15/15 通过**（PenaltyRulesTest 5 + ColdChainCaseServiceTest 7 + InventoryConsumePurposeTest 3）
- `mvn package`：构建成功
- 本地 PostgreSQL 15（用户态实例 5433）启动应用：正常，演示数据自动初始化（5 供应商含应急乳品供应商、12 食材、5 个角色账号）
- `python3 scripts/smoke.py`：**83/83 全部通过**（含冷链温度超标专项 30 项：暂扣取证/拒收联动/降级双闸门/复检放行/用途管制/权限拦截）
- 拒收案件结算逐项核对：退货扣减 -480、拒收违约金索赔 -96（已核准直接入账）、降级折价 -96、复检合格批次正常付款、冷链扣罚两行各 -300（复检合格批次扣罚免除）
- Swagger UI `/swagger-ui/index.html` 返回 200，`/v3/api-docs` 返回 OpenAPI 3.0.1 文档（66 个路径，含冷链处置 9 个端点与受限库存领用/查询 2 个端点）
- 注：本环境无 Docker，`docker compose up --build` 未实际执行；Compose 配置与本地验证使用相同的镜像构建路径（Dockerfile 多阶段构建）与连接参数

## 项目结构

```
src/main/java/com/campus/food/
├── config/        # 安全、OpenAPI、全局异常、演示数据初始化
├── domain/        # 26 个 JPA 实体 + enums/ 28 个枚举
├── repo/          # 26 个 Spring Data JPA 仓库
├── service/       # 12 个业务服务（菜单/计划/送货/验收/冷链处置/库存/退货/索赔/结算/异常/影响/统计）
└── web/           # 16 个 REST 控制器
scripts/smoke.py   # 端到端冒烟验证脚本（83 项断言）
Dockerfile         # 多阶段构建（maven 构建 → jre 运行）
docker-compose.yml # app + postgres 一键启动
```

## 关键设计

- **一致性**：验收在一个事务内联动库存流水、退货单、异常与索赔；结算金额完全由验收明细、退货、折价、索赔、扣罚推导，可追溯到行级明细
- **少送判定**：按订货明细的累计送货量（跨批次）判定，避免分批送货误报
- **无法拒收**：拒收数量若被"已备餐"餐次需要，自动转附条件接收（照常入库）+ 异常 + 货值 50% 折价索赔
- **降级使用**：验收登记降级数量 → 采购负责人审批 → 批准按折价率入库并参与折价结算，驳回自动转退货
- **冷链温度超标闭环**：验收取证（到货温度/运输时长/产品批次/抽检照片）→ 问题货整批 `heldQty` 暂扣（不计库存、不进结算）→ 食堂三选一：
  - **拒收**：退货 + 货值 20% 违约金索赔（自动核准，结算周期内直接扣减）+ 影响菜单时生成替代采购单、菜单调整记录与家长公告草稿；
  - **降级**：用途锁定为教职工餐或非即食加工，采购折价审批 + 质检复查双闸门通过才入受限库存，`/api/inventory/consume` 按用途强制管制，学生餐链路物理上无法领用降级批次；
  - **等待复检**：质检复测合格则正常入库并把该案件冷链异常扣罚清零关闭，复测不合格退回食堂重新处置；
  - 案件全部结案才重算验收单结果并将订货单置为已验收
- **复盘归因**：异常关闭时必须归因（天气/供应商履约/验收执行偏差），统计接口按供应商、品类、验收员三个维度输出
