# 校园食材供应商订货验收入库与索赔服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的后端服务，覆盖校园食堂食材供应链全流程：

```
周菜单 → 采购计划 → 订货单 → 送货批次 → 验收 → 入库/退货/降级 → 索赔 → 结算 → 影响分析 → 复盘统计
```

## 功能总览

| 模块 | 说明 |
|---|---|
| 周菜单 | 食堂按周维护餐次与食材用量，发布后生成采购计划 |
| 采购计划 | 汇总菜单需求，扣除可用库存（保留安全线）得出采购量，按品类匹配供应商 |
| 订货单 | 按计划按供应商自动拆单，记录规格、数量、合同价、质检要求、是否冷链、要求到货时间 |
| 送货批次 | 登记车牌、司机、到货时间、车厢温度；自动识别**晚送 / 冷链温度超标 / 临时替换品类** |
| 冷链温度超标验收 | 验收员登记到货温度、运输时长、产品批次、抽检照片；食堂选择**拒收 / 降级为非学生餐使用 / 等待复检**。拒收自动生成退货单与质量索赔（直连结算），影响当天菜单时同步生成**替代采购与菜单调整记录**；降级批次限入**教职工餐/非即食加工**的限制用途库存，经**质检人员复查合格后才入库**；复检合格正常入库、不合格转拒收 |
| 验收 | 验收员逐项核对重量、腐烂比例、检疫票、外观、抽检；合格入库、拒收退货、降级待审批；自动识别**少送（按累计送货量）/ 腐烂超标 / 票证缺失**；**食堂已备餐无法拒收**时自动转附条件接收并生成折价索赔；走冷链处置流程的明细按处置结论（拒收/降级/合格）落账且不重复入库、退货、索赔 |
| 库存 | 库存水位 + 流水（入库/出库/退货/调整），安全线预警 |
| 退货 | 拒收与降级驳回自动生成退货单，跟踪退货完成 |
| 索赔 | 手工登记或系统自动生成（无法拒收折价），审批/支付流转 |
| 结算 | 按实际验收重量×合同价，扣减退货、降级折价、索赔、合同扣罚生成账单；确认时录入供应商开票金额，不一致自动登记**结算差异异常** |
| 影响分析 | 验收失败批次 → 受影响餐次、替代食材（替代组→同品类）、家长公告草稿、供应商责任 |
| 复盘统计 | 按供应商/品类/验收员统计异常与拒收率；按归因区分**天气质量波动 / 供应商履约不稳 / 验收标准执行偏差** |

## 异常类型与合同扣罚规则

| 异常 | 触发时机 | 扣罚 |
|---|---|---|
| 晚送 LATE_DELIVERY | 送货登记（宽限 30 分钟） | 订单金额 5%，最低 200 |
| 冷链超标 COLD_CHAIN_BREACH | 送货登记（车厢温度超出食材冷链区间） | 冷链货值 5%，最低 300 |
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
| inspector | inspector123 | 验收员 | 创建验收记录、登记冷链温度超标取证 |
| buyer | buyer123 | 采购负责人 | 供应商/食材/下单/送货登记/降级审批/退货/索赔/结算/异常处理/替代采购到货 |
| canteen | canteen123 | 食堂 | 周菜单/生成采购计划/库存领用/冷链超标处置决策（拒收/降级/复检）/限制用途领用 |
| quality | quality123 | 质检人员 | 降级批次复查放行/驳回、等待复检批次录入实验室复检结果 |
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

脚本以 5 个角色走完整流程并断言 85 项检查：权限拦截 → 菜单/计划/拆单 → 送货（晚送+冷链+替换）→ 验收（少送/腐烂/缺票证/降级）→ 库存与安全线 → 降级审批/退货 → 影响分析与家长公告 → 已备餐无法拒收（附条件接收+自动索赔）→ **冷链温度超标验收（酸奶到货 9℃ 拒收：退货+自动索赔+替代采购+菜单调整；鲜牛奶 8℃ 降级：用途限制+质检复查后入限制库存+限制领用；鲜牛奶 7℃ 等待复检：复检合格正常入库；复查不合格自动转拒收）** → 冷链索赔/降级折让进入结算 → 索赔 → 结算（金额逐项断言+对账差异）→ 供应商看板/异常归因/复盘统计 → 未认证拦截与 OpenAPI 文档。

### 3. 冷链温度超标验收手工体验（curl）

```bash
# 1) 验收员登记冷链超标（到货温度/运输时长/产品批次/抽检照片）
curl -u inspector:inspector123 -X POST http://localhost:8080/api/cold-chain/cases \
  -H 'Content-Type: application/json' \
  -d '{"batchId":5,"deliveryItemId":9,"arrivalTemperature":9,"transportMinutes":180,
       "productBatchNo":"SUANNAI-20261007-A","notes":"车厢温度偏高",
       "photos":[{"photoUrl":"https://example.com/yogurt-temp.jpg","caption":"车厢温度计9℃"}]}'
# 2) 食堂处置：REJECTION 拒收 / DOWNGRADE 降级(需 usageScope=STAFF_MEAL|COOKED_ONLY) / RECHECK 等待复检
curl -u canteen:canteen123 -X POST http://localhost:8080/api/cold-chain/cases/1/decision \
  -H 'Content-Type: application/json' -d '{"disposition":"REJECTION","note":"温度超标拒收"}'
# 3a) 降级批次：质检复查合格（入限制用途库存）/ 不合格（自动转拒收）
curl -u quality:quality123 -X POST http://localhost:8080/api/cold-chain/cases/2/qc-review \
  -H 'Content-Type: application/json' -d '{"pass":true,"discountRate":0.3,"note":"快检合格，限非即食加工"}'
# 3b) 等待复检批次：录入实验室复检结果
curl -u quality:quality123 -X POST http://localhost:8080/api/cold-chain/cases/3/lab-result \
  -H 'Content-Type: application/json' -d '{"pass":true,"labReportNo":"LAB2026100901","note":"微生物合格"}'
# 4) 查看拒收联动的替代采购与菜单调整
curl -u canteen:canteen123 http://localhost:8080/api/cold-chain/cases/1
# 5) 限制用途库存（不进入学生餐可用库存）与领用
curl -u canteen:canteen123 http://localhost:8080/api/inventory/restricted-levels
```

处置完成后验收员再对该批次做 `POST /api/acceptances`，冷链明细按处置结论落账；拒收退货、质量索赔、降级折让均自动汇入 `/api/settlements/generate` 账单。

### 4. 其他手工体验（curl 示例）

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

- `mvn test`：5/5 通过（PenaltyRulesTest 扣罚规则，含冷链超温最低 300/按货值 5% 计罚）
- `mvn package`：构建成功
- 本地 PostgreSQL 15（临时实例 5433）启动应用：正常，演示数据自动初始化（含新增酸奶食材与质检账号）
- `python3 scripts/smoke.py`：**85/85 全部通过**（含冷链温度超标验收拒收/降级/复检 3 条处置链路、替代采购、菜单调整、限制用途库存、结算联动）
- 另手工验证"降级质检复查不通过 → 自动转拒收+退货+索赔"分支：结论正确
- Swagger UI `/swagger-ui/index.html` 返回 200，`/v3/api-docs` 返回 OpenAPI 3.0.1 文档（66 个路径，新增 11 个冷链相关路径）
- 注：本环境无 Docker，`docker compose up --build` 未实际执行；Compose 配置与本地验证使用相同的镜像构建路径（Dockerfile 多阶段构建）与连接参数

## 项目结构

```
src/main/java/com/campus/food/
├── config/        # 安全、OpenAPI、全局异常、演示数据初始化
├── domain/        # 27 个 JPA 实体 + enums/ 24 个枚举
├── repo/          # 27 个 Spring Data JPA 仓库
├── service/       # 13 个业务服务（菜单/计划/送货/冷链超标处置/验收/库存/退货/索赔/结算/异常/影响/统计）
└── web/           # 16 个 REST 控制器
scripts/smoke.py   # 端到端冒烟验证脚本
Dockerfile         # 多阶段构建（maven 构建 → jre 运行）
docker-compose.yml # app + postgres 一键启动
```

## 关键设计

- **一致性**：验收在一个事务内联动库存流水、退货单、异常与索赔；结算金额完全由验收明细、退货、折价、索赔、扣罚推导，可追溯到行级明细
- **少送判定**：按订货明细的累计送货量（跨批次）判定，避免分批送货误报
- **无法拒收**：拒收数量若被"已备餐"餐次需要，自动转附条件接收（照常入库）+ 异常 + 货值 50% 折价索赔
- **降级使用**：验收登记降级数量 → 采购负责人审批 → 批准按折价率入库并参与折价结算，驳回自动转退货
- **冷链温度超标验收**：验收员取证（到货温度/运输时长/产品批次/抽检照片）→ 食堂决策三选一：
  - *拒收*：生成退货单 + 货值 10% 质量索赔（自动批准，结算直接扣减）+ 冷链合同扣罚（货值 5%，最低 300）；若当天在排/已备餐餐次库存不足，按餐次自动生成替代采购记录（建议替代食材/紧急供应商）与菜单调整记录（同菜换料/换菜、影响份数）
  - *降级*：必须指定用途（教职工餐 STAFF_MEAL / 非即食加工 COOKED_ONLY），批次先挂起不入库，质检复查合格才计入独立的限制用途库存（RESTRICTED_IN 流水），复查不合格自动转拒收；限制库存不进入学生餐可用水位，领用走专用接口并记 RESTRICTED_OUT 流水
  - *等待复检*：挂起待实验室复检，合格正常入库（学生餐可用），不合格转拒收
  - 最终验收时冷链明细按处置结论落账（拒收/降级 APPROVED/合格），入库、退货、索赔、折让均不重复执行，金额统一汇入供应商结算
- **复盘归因**：异常关闭时必须归因（天气/供应商履约/验收执行偏差），统计接口按供应商、品类、验收员三个维度输出
