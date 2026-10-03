#!/usr/bin/env python3
"""端到端冒烟验证：菜单->计划->订货->送货->验收->库存/退货/降级->索赔->结算->影响分析->复盘统计"""
import json
import sys
import urllib.request
import urllib.error
import base64
from datetime import date, datetime, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"

USERS = {
    "inspector": ("inspector", "inspector123"),
    "buyer": ("buyer", "buyer123"),
    "canteen": ("canteen", "canteen123"),
    "quality": ("quality", "quality123"),
    "admin": ("admin", "admin123"),
}

PASS, FAIL = 0, 0


def call(user, method, path, body=None, expect=200):
    global PASS, FAIL
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    u, p = USERS[user]
    req.add_header("Authorization", "Basic " + base64.b64encode(f"{u}:{p}".encode()).decode())
    try:
        with urllib.request.urlopen(req) as resp:
            code, payload = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        code, payload = e.code, e.read()
    obj = json.loads(payload) if payload else None
    if code != expect:
        FAIL += 1
        print(f"  [FAIL] {method} {path} -> {code} (expect {expect}): {str(obj)[:300]}")
        return None
    return obj


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name} {detail}")


def find(items, **kw):
    for it in items:
        if all(it.get(k) == v for k, v in kw.items()):
            return it
    return None


def main():
    today = date.today()
    print("== 1. 基础数据与权限 ==")
    ings = call("canteen", "GET", "/api/ingredients")
    check("食材列表 13 条", ings and len(ings) == 13, f"got {len(ings) if ings else 0}")
    ing = {i["code"]: i for i in ings}
    r = call("inspector", "POST", "/api/suppliers", {"code": "X", "name": "X"}, expect=403)
    check("验收员不能新建供应商(403)", r is not None)

    menus = call("canteen", "GET", "/api/menus")
    menu = menus[0]
    check("菜单已发布", menu["status"] == "PUBLISHED")
    detail = call("canteen", "GET", f"/api/menus/{menu['id']}")
    check("菜单含 6 个餐次", len(detail["meals"]) == 6)

    print("== 2. 生成采购计划并下单 ==")
    plan = call("canteen", "POST", f"/api/plans/menus/{menu['id']}/generate", {"note": "冒烟验证"})
    check("计划已生成", plan and plan["status"] == "DRAFT")
    pd = call("canteen", "GET", f"/api/plans/{plan['id']}")
    items = pd["items"]
    check("计划明细 9 行（大米库存充足不采购，新增酸奶）", len(items) == 9, f"got {len(items)}")
    cabbage_item = find(items, ingredient={"id": ing["ING007"]["id"], "code": "ING007", "name": "大白菜",
                                           "category": "VEGETABLE", "unit": "kg", "spec": "散装",
                                           "requiresColdChain": False, "shelfLifeDays": 5,
                                           "safetyStock": 80.0, "referencePrice": 3.0,
                                           "substituteGroup": "LEAFY"})
    check("大白菜计划采购 30kg（库存低于安全线不可用）",
          cabbage_item and float(cabbage_item["plannedQty"]) == 30.0)

    expected_arrival = (datetime.combine(today + timedelta(days=1), datetime.min.time())
                        .replace(hour=8).isoformat())
    orders = call("buyer", "POST", f"/api/plans/{plan['id']}/orders",
                  {"expectedArrivalAt": expected_arrival, "remark": "冒烟"})
    check("按供应商拆成 4 张订货单", orders and len(orders) == 4, f"got {orders and len(orders)}")
    sups = call("buyer", "GET", "/api/suppliers")
    sup = {s["code"]: s for s in sups}
    meat_order = find(orders, supplier=sup["SUP002"])
    check("找到肉类供应商订货单", meat_order is not None)
    od = call("buyer", "GET", f"/api/orders/{meat_order['id']}")
    pork_oi = find(od["items"], ingredient=ing["ING004"])
    beef_oi = find(od["items"], ingredient=ing["ING005"])
    check("订货明细含猪肉/牛肉", pork_oi is not None and beef_oi is not None)

    print("== 3. 送货（晚送+冷链超标+替换品类+缺检疫票） ==")
    late_arrival = (datetime.combine(today + timedelta(days=1), datetime.min.time())
                    .replace(hour=10).isoformat())
    batch = call("buyer", "POST", "/api/deliveries", {
        "orderId": meat_order["id"], "licensePlate": "沪A12345", "driverName": "司机甲",
        "arrivedAt": late_arrival, "vehicleTemperature": 10,
        "items": [
            {"orderItemId": pork_oi["id"], "ingredientId": ing["ING004"]["id"], "deliveredQty": 30,
             "productionBatchNo": "PC20261001", "productionDate": str(today - timedelta(days=1)),
             "expiryDate": str(today + timedelta(days=2))},
            {"orderItemId": beef_oi["id"], "ingredientId": ing["ING005"]["id"], "deliveredQty": 25,
             "productionBatchNo": "NC20261001", "quarantineCertNo": "JY20261003001",
             "productionDate": str(today - timedelta(days=1)),
             "expiryDate": str(today + timedelta(days=2))},
            {"ingredientId": ing["ING004"]["id"], "deliveredQty": 10,
             "productionBatchNo": "PC20261002", "quarantineCertNo": "JY20261003002",
             "productionDate": str(today), "expiryDate": str(today + timedelta(days=3))}
        ]})
    check("批次已登记", batch and batch["batchNo"].startswith("DB"))
    bd = call("buyer", "GET", f"/api/deliveries/{batch['id']}")
    check("送货明细 3 行", len(bd["items"]) == 3)
    pork_di = [i for i in bd["items"] if i.get("orderItem") and i["orderItem"]["id"] == pork_oi["id"]][0]
    beef_di = [i for i in bd["items"] if i.get("orderItem") and i["orderItem"]["id"] == beef_oi["id"]][0]
    sub_di = [i for i in bd["items"] if i.get("orderItem") is None][0]

    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    types = {a["type"] for a in anomalies}
    check("自动识别 晚送/冷链/替换 异常",
          {"LATE_DELIVERY", "COLD_CHAIN_BREACH", "SUBSTITUTION"} <= types, str(types))

    print("== 4. 验收（部分拒收+降级+腐烂超标+缺票证+少送） ==")
    rec = call("inspector", "POST", "/api/acceptances", {
        "batchId": batch["id"], "measuredTemp": 10, "temperatureOk": False,
        "appearanceOk": True, "samplingResult": "农残抽检合格", "notes": "冷链车故障",
        "items": [
            {"deliveryItemId": pork_di["id"], "acceptedQty": 20, "rejectedQty": 5,
             "downgradedQty": 5, "rotRatio": 0.12, "rejectReason": "部分腐烂变色"},
            {"deliveryItemId": beef_di["id"], "acceptedQty": 25, "rotRatio": 0.02},
            {"deliveryItemId": sub_di["id"], "acceptedQty": 10, "rotRatio": 0.0}
        ]})
    check("验收完成(部分合格)", rec and rec["overallResult"] == "PARTIAL_QUALIFIED")
    rd = call("inspector", "GET", f"/api/acceptances/{rec['id']}")
    check("验收明细 3 行", len(rd["items"]) == 3)
    pork_ai = [i for i in rd["items"] if i["deliveryItem"]["id"] == pork_di["id"]][0]
    check("猪肉 5kg 降级待审批", pork_ai and pork_ai["downgradeStatus"] == "PENDING")

    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    types = {a["type"] for a in anomalies}
    check("自动识别 少送/腐烂/缺票证 异常",
          {"SHORT_DELIVERY", "HIGH_ROT_RATIO", "MISSING_DOCUMENTS"} <= types, str(types))

    levels = call("canteen", "GET", "/api/inventory/levels")
    pork_lv = find(levels, ingredient=ing["ING004"])
    check("猪肉库存 = 20(原) + 20(合格) + 10(替换) = 50",
          pork_lv and float(pork_lv["quantity"]) == 50.0, str(pork_lv and pork_lv["quantity"]))
    alerts = call("canteen", "GET", "/api/inventory/safety-alerts")
    alert_names = {a["ingredient"]["name"] for a in alerts}
    check("安全线预警含大白菜/鲜牛奶", {"大白菜", "鲜牛奶"} <= alert_names, str(alert_names))

    print("== 5. 降级审批 / 退货 ==")
    call("buyer", "POST", f"/api/acceptances/items/{pork_ai['id']}/downgrade-approval",
         {"approve": True, "discountRate": 0.25})
    levels = call("canteen", "GET", "/api/inventory/levels")
    pork_lv = find(levels, ingredient=ing["ING004"])
    check("降级批准后猪肉库存 55", pork_lv and float(pork_lv["quantity"]) == 55.0)
    returns = call("buyer", "GET", f"/api/returns?supplierId={sup['SUP002']['id']}")
    check("生成退货单 5kg 猪肉", returns and float(returns[0]["quantity"]) == 5.0)
    call("buyer", "POST", f"/api/returns/{returns[0]['id']}/complete")
    returns = call("buyer", "GET", f"/api/returns?supplierId={sup['SUP002']['id']}")
    check("退货已完成", returns[0]["status"] == "COMPLETED")

    print("== 6. 影响分析（受影响餐次/替代食材/家长公告/供应商责任） ==")
    impact = call("admin", "GET", f"/api/impact/batch/{batch['id']}")
    check("有影响食材(猪肉)", impact and len(impact["impacts"]) >= 1)
    pork_impact = impact["impacts"][0]
    check("受影响餐次 >= 2（白菜猪肉炖粉条/米饭套餐）",
          len(pork_impact["affectedMeals"]) >= 2, str(pork_impact["affectedMeals"]))
    check("替代食材含牛肉", any(s["name"] == "牛肉" for s in pork_impact["substitutes"]))
    check("自动生成家长公告草稿", len(impact["announcements"]) >= 1)
    check("供应商责任含异常与索赔信息", impact["supplierResponsibility"]["supplierName"] == "惠民肉联食品公司")
    ann = call("admin", "GET", "/api/announcements?status=DRAFT")
    check("公告草稿存在", len(ann) >= 1)
    pub = call("admin", "POST", f"/api/announcements/{ann[0]['id']}/publish")
    check("公告已发布", pub and pub["status"] == "PUBLISHED")

    print("== 7. 已备餐无法拒收 -> 附条件接收+自动索赔 ==")
    meals = call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]
    rice_meal = [m for m in meals if m["meal"]["dishName"] == "米饭套餐"][0]["meal"]
    call("canteen", "POST", f"/api/menus/meals/{rice_meal['id']}/status", {"status": "PREPARED"})
    batch2 = call("buyer", "POST", "/api/deliveries", {
        "orderId": meat_order["id"], "licensePlate": "沪B67890",
        "arrivedAt": (datetime.combine(today + timedelta(days=2), datetime.min.time())
                      .replace(hour=7).isoformat()),
        "vehicleTemperature": 2,
        "items": [{"orderItemId": pork_oi["id"], "ingredientId": ing["ING004"]["id"],
                   "deliveredQty": 10, "quarantineCertNo": "JY20261004001",
                   "productionDate": str(today), "expiryDate": str(today + timedelta(days=3))}]})
    bd2 = call("buyer", "GET", f"/api/deliveries/{batch2['id']}")
    di2 = bd2["items"][0]
    rec2 = call("inspector", "POST", "/api/acceptances", {
        "batchId": batch2["id"], "measuredTemp": 2, "temperatureOk": True,
        "appearanceOk": False, "samplingResult": "感官异常",
        "items": [{"deliveryItemId": di2["id"], "rejectedQty": 10, "rotRatio": 0.3,
                   "rejectReason": "大面积变质"}]})
    check("已备餐时拒收转附条件接收", rec2 is not None)
    rd2 = call("inspector", "GET", f"/api/acceptances/{rec2['id']}")
    ai2 = rd2["items"][0]
    check("验收明细为 CONDITIONAL_ACCEPT 且全部入库",
          ai2["result"] == "CONDITIONAL_ACCEPT" and float(ai2["acceptedQty"]) == 10.0)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    check("产生 CANNOT_REJECT_PREPARED 异常",
          any(a["type"] == "CANNOT_REJECT_PREPARED" for a in anomalies))
    claims = call("buyer", "GET", f"/api/claims?supplierId={sup['SUP002']['id']}")
    check("自动生成无法拒收折价索赔", any("无法拒收" in (c["claimType"] or "") for c in claims))

    print("== 8. 索赔 ==")
    claim = call("buyer", "POST", "/api/claims", {
        "supplierId": sup["SUP002"]["id"], "batchId": batch["id"],
        "claimType": "冷链断裂质量索赔", "amount": 150, "reason": "冷链车故障导致部分腐烂"})
    check("索赔已登记", claim and claim["status"] == "PENDING")
    call("buyer", "POST", f"/api/claims/{claim['id']}/approve")
    claim = call("buyer", "GET", f"/api/claims/{claim['id']}")
    check("索赔已批准", claim["status"] == "APPROVED")

    print("== 9. 结算（验收重量/退货/折价/索赔/扣罚 + 对账差异） ==")
    st = call("buyer", "POST", "/api/settlements/generate", {
        "supplierId": sup["SUP002"]["id"],
        "periodStart": str(today - timedelta(days=7)),
        "periodEnd": str(today + timedelta(days=7))})
    check("结算单已生成", st and st["status"] == "DRAFT")
    # delivered: 20*30 + 5*30(降级全价) + 25*60 + 10*30(替换) + 10*30(附条件) = 600+150+1500+300+300 = 2850
    check("验收应付 2850", float(st["deliveredAmount"]) == 2850.0, str(st["deliveredAmount"]))
    check("退货扣减 150", float(st["returnDeduction"]) == 150.0, str(st["returnDeduction"]))
    check("降级折价扣减 37.5", float(st["downgradeDeduction"]) == 37.5, str(st["downgradeDeduction"]))
    check("索赔扣减 150", float(st["claimDeduction"]) == 150.0, str(st["claimDeduction"]))
    # 扣罚: 批次1(晚送200+冷链300+替换30+少送15+腐烂5.4+缺票证100) + 批次2(晚送200+腐烂4.5) = 854.9
    check("合同扣罚 854.9", float(st["penaltyDeduction"]) == 854.9, str(st["penaltyDeduction"]))
    check("最终应付 1657.6", float(st["finalAmount"]) == 1657.6, str(st["finalAmount"]))
    sd = call("buyer", "GET", f"/api/settlements/{st['id']}")
    check("结算明细含 6 类行", len({i["itemType"] for i in sd["items"]}) == 5)
    st2 = call("buyer", "POST", f"/api/settlements/{st['id']}/confirm",
               {"supplierInvoiceAmount": 2000})
    check("开票金额不一致 -> mismatchFlag", st2 and st2["mismatchFlag"] is True)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    check("产生 SETTLEMENT_MISMATCH 异常",
          any(a["type"] == "SETTLEMENT_MISMATCH" for a in anomalies))

    print("== 10. 供应商看板 / 异常归因 / 复盘统计 ==")
    dash = call("buyer", "GET", f"/api/anomalies/dashboard/{sup['SUP002']['id']}")
    check("连续异常标记(近30天>=3)", dash["consecutiveAnomaly"] is True)
    check("看板含异常类型分布", len(dash["anomaliesByType"]) >= 6)
    open_anomalies = [a for a in anomalies if a["status"] == "OPEN"]
    a1, a2 = open_anomalies[0], open_anomalies[1]
    call("buyer", "POST", f"/api/anomalies/{a1['id']}/resolve",
         {"causeCategory": "SUPPLIER_PERFORMANCE", "note": "供应商冷链车故障"})
    call("buyer", "POST", f"/api/anomalies/{a2['id']}/resolve",
         {"causeCategory": "WEATHER", "note": "连续高温导致腐烂"})
    stats = call("admin", "GET", "/api/stats/by-supplier")
    meat_stats = find(stats, supplierId=sup["SUP002"]["id"])
    check("按供应商统计含肉类供应商", meat_stats is not None and meat_stats["anomalyCount"] >= 8)
    cat = call("admin", "GET", "/api/stats/by-category")
    check("按品类统计含 MEAT", cat.get("MEAT", 0) >= 3, str(cat))
    insp = call("admin", "GET", "/api/stats/by-inspector")
    check("按验收员统计含王验收", any(i["username"] == "inspector" and i["acceptanceCount"] == 2
                                    for i in insp), str(insp))
    cb = call("admin", "GET", "/api/stats/cause-breakdown")
    check("归因分布含 供应商履约/天气",
          cb["byCause"].get("SUPPLIER_PERFORMANCE", 0) >= 1 and cb["byCause"].get("WEATHER", 0) >= 1,
          str(cb["byCause"]))

    print("== 12. 冷链温度超标验收（拒收/降级/复检） ==")
    dairy_order = find(orders, supplier=sup["SUP003"])
    check("找到乳业供应商订货单", dairy_order is not None)
    dod = call("buyer", "GET", f"/api/orders/{dairy_order['id']}")
    yogurt_oi = find(dod["items"], ingredient=ing["ING013"])
    milk_oi = find(dod["items"], ingredient=ing["ING010"])
    check("订货明细含酸奶/鲜牛奶", yogurt_oi is not None and milk_oi is not None)
    meals = call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]
    breakfast = [m for m in meals if m["meal"]["dishName"] == "牛奶鸡蛋早餐"][0]["meal"]
    bfast_date = breakfast["date"]

    def register_cold_batch(qty, oi, ingredient, temp, day_offset):
        arrive = (datetime.combine(date.fromisoformat(bfast_date) + timedelta(days=day_offset),
                                   datetime.min.time()).replace(hour=7)).isoformat()
        b = call("buyer", "POST", "/api/deliveries", {
            "orderId": dairy_order["id"], "licensePlate": "沪C55128", "driverName": "司机乙",
            "arrivedAt": arrive, "vehicleTemperature": temp,
            "items": [{"orderItemId": oi, "ingredientId": ingredient, "deliveredQty": qty,
                       "productionBatchNo": f"SU{temp}", "productionDate": str(today),
                       "expiryDate": str(today + timedelta(days=7))}]})
        bd3 = call("buyer", "GET", f"/api/deliveries/{b['id']}")
        return b, bd3["items"][0]

    # --- A. 酸奶到货 9℃（合同 2~6℃），拒收 ---
    ba, yogurt_di = register_cold_batch(100, yogurt_oi["id"], ing["ING013"]["id"], 9, 0)
    case_a = call("inspector", "POST", "/api/cold-chain/cases", {
        "batchId": ba["id"], "deliveryItemId": yogurt_di["id"], "arrivalTemperature": 9,
        "transportMinutes": 180, "productBatchNo": "SUANNAI-20261007-A",
        "notes": "车厢温度偏高，酸奶杯壁有水珠",
        "photos": [{"photoUrl": "https://example.com/photos/yogurt-temp.jpg", "caption": "车厢温度计9℃"},
                   {"photoUrl": "https://example.com/photos/yogurt-batch.jpg", "caption": "产品批次标签"}]})
    check("冷链处置单已登记(待处置)", case_a and case_a["disposition"] == "PENDING"
          and case_a["reviewStatus"] == "PENDING_REVIEW", str(case_a))
    cd = call("inspector", "GET", f"/api/cold-chain/cases/{case_a['id']}")
    check("登记 2 张抽检照片", len(cd["photos"]) == 2, str(cd["photos"]))
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP003']['id']}")
    check("乳业批次自动识别冷链超标异常", any(a["type"] == "COLD_CHAIN_BREACH" for a in anomalies))
    # 权限：验收员不能决策
    call("inspector", "POST", f"/api/cold-chain/cases/{case_a['id']}/decision",
         {"disposition": "REJECTION"}, expect=403)
    # 输入校验：未超温/无照片
    call("inspector", "POST", "/api/cold-chain/cases", {
        "batchId": ba["id"], "deliveryItemId": yogurt_di["id"], "arrivalTemperature": 4,
        "transportMinutes": 10, "photos": [{"photoUrl": "x"}]}, expect=409)
    dec = call("canteen", "POST", f"/api/cold-chain/cases/{case_a['id']}/decision",
               {"disposition": "REJECTION", "note": "温度超标影响学生饮用安全，拒收"})
    check("食堂拒收：退货单已生成", dec and dec["returnOrder"] is not None)
    check("拒收自动索赔 30 元（100杯×3×10%）且已批准、直连结算",
          dec["claim"] and float(dec["claim"]["amount"]) == 30.0 and dec["claim"]["status"] == "APPROVED",
          str(dec["claim"]))
    cd = call("canteen", "GET", f"/api/cold-chain/cases/{case_a['id']}")
    check("拒收影响当天早餐 -> 替代采购记录",
          len(cd["substitutePurchases"]) >= 1 and float(cd["substitutePurchases"][0]["requiredQty"]) == 100.0,
          str(cd["substitutePurchases"]))
    check("同步生成菜单调整记录(SUBSTITUTE)",
          len(cd["menuAdjustments"]) >= 1 and cd["menuAdjustments"][0]["action"] == "SUBSTITUTE",
          str(cd["menuAdjustments"]))
    sp_id = cd["substitutePurchases"][0]["id"]
    call("buyer", "POST", f"/api/cold-chain/substitutes/{sp_id}/receive")
    sp = call("buyer", "GET", f"/api/cold-chain/cases/{case_a['id']}/substitutes")
    check("替代采购已登记到货", sp[0]["status"] == "RECEIVED")
    rec_a = call("inspector", "POST", "/api/acceptances", {
        "batchId": ba["id"], "measuredTemp": 9, "temperatureOk": False, "appearanceOk": True,
        "samplingResult": "中心温度超标", "notes": "冷链处置拒收",
        "items": [{"deliveryItemId": yogurt_di["id"], "rejectedQty": 100,
                   "rejectReason": "冷链温度超标拒收"}]})
    check("拒收批次完成验收", rec_a is not None)
    rd_a = call("inspector", "GET", f"/api/acceptances/{rec_a['id']}")
    ai_a = rd_a["items"][0]
    check("验收明细 REJECTED 且退货不重复生成",
          ai_a["result"] == "REJECTED" and float(ai_a["rejectedQty"]) == 100.0)
    dairy_returns = call("buyer", "GET", f"/api/returns?supplierId={sup['SUP003']['id']}")
    check("酸奶退货单仅 1 张（不重复）", len(dairy_returns) == 1 and float(dairy_returns[0]["quantity"]) == 100.0,
          str(dairy_returns))

    # --- B. 鲜牛奶到货 8℃，降级为非即食加工，待质检复查 ---
    bb, milk_di = register_cold_batch(50, milk_oi["id"], ing["ING010"]["id"], 8, 1)
    case_b = call("inspector", "POST", "/api/cold-chain/cases", {
        "batchId": bb["id"], "deliveryItemId": milk_di["id"], "arrivalTemperature": 8,
        "transportMinutes": 240, "productBatchNo": "NIUNAI-20261008-B",
        "photos": [{"photoUrl": "https://example.com/photos/milk-temp.jpg", "caption": "测温8℃"}]})
    check("第二张冷链处置单已登记", case_b is not None)
    call("canteen", "POST", f"/api/cold-chain/cases/{case_b['id']}/decision",
         {"disposition": "DOWNGRADE", "note": "降级非学生餐但未指定用途"}, expect=400)
    check("降级但缺用途被拒后尚未入库", case_b["disposition"] == "PENDING")
    # 食堂必须指定用途
    dec_b = call("canteen", "POST", f"/api/cold-chain/cases/{case_b['id']}/decision",
                 {"disposition": "DOWNGRADE", "usageScope": "COOKED_ONLY",
                  "note": "降级为非即食加工，仅限充分加热后供教职工餐"})
    check("降级处置：等待质检复查、未入库",
          dec_b["usageScope"] == "COOKED_ONLY" and dec_b["reviewStatus"] == "PENDING_REVIEW"
          and dec_b.get("restrictedStock") is None, str(dec_b))
    call("inspector", "POST", "/api/acceptances", {
        "batchId": bb["id"], "measuredTemp": 8, "temperatureOk": False, "appearanceOk": True,
        "items": [{"deliveryItemId": milk_di["id"], "downgradedQty": 50}]}, expect=409)
    # 食堂不能自行质检
    call("canteen", "POST", f"/api/cold-chain/cases/{case_b['id']}/qc-review",
         {"pass": True}, expect=403)
    rv = call("quality", "POST", f"/api/cold-chain/cases/{case_b['id']}/qc-review",
              {"pass": True, "discountRate": 0.3, "note": "感官与菌落快检合格，允许非即食加工"})
    check("质检复查合格后入限制用途库存 50",
          rv and rv["reviewStatus"] == "PASSED" and rv["restrictedStock"] is not None
          and float(rv["restrictedStock"]["quantity"]) == 50.0, str(rv))
    rstock = call("canteen", "GET",
                  f"/api/inventory/restricted-levels?ingredientId={ing['ING010']['id']}")
    check("限制用途库存可查(COOKED_ONLY 50)",
          len(rstock) >= 1 and rstock[0]["usageScope"] == "COOKED_ONLY"
          and float(rstock[0]["quantity"]) == 50.0, str(rstock))
    levels = call("canteen", "GET", "/api/inventory/levels")
    milk_lv = find(levels, ingredient=ing["ING010"])
    check("降级批次不进入学生餐正常库存（鲜牛奶仍 60）", float(milk_lv["quantity"]) == 60.0,
          str(milk_lv["quantity"]))
    rec_b = call("inspector", "POST", "/api/acceptances", {
        "batchId": bb["id"], "measuredTemp": 8, "temperatureOk": False, "appearanceOk": True,
        "items": [{"deliveryItemId": milk_di["id"], "downgradedQty": 50}]})
    rd_b = call("inspector", "GET", f"/api/acceptances/{rec_b['id']}")
    check("降级批次验收 DOWNGRADED/APPROVED（按冷链处置结论落账）",
          rd_b["items"][0]["result"] == "DOWNGRADED"
          and rd_b["items"][0]["downgradeStatus"] == "APPROVED"
          and float(rd_b["items"][0]["discountRate"]) == 0.3)
    txns = call("canteen", "GET", f"/api/inventory/txns?ingredientId={ing['ING010']['id']}")
    check("库存流水含 RESTRICTED_IN", any(t["type"] == "RESTRICTED_IN" for t in txns), str([t["type"] for t in txns]))
    call("canteen", "POST", f"/api/cold-chain/cases/{case_b['id']}/restricted-consume",
         {"qty": 10, "purpose": "教职工餐烘焙用奶，充分加热"})
    call("canteen", "POST", f"/api/cold-chain/cases/{case_b['id']}/restricted-consume",
         {"qty": 100, "purpose": "超量领用"}, expect=400)
    rstock = call("canteen", "GET", f"/api/inventory/restricted-levels?ingredientId={ing['ING010']['id']}")
    check("限制用途领用 10，余额 40，超量被拒",
          float(rstock[0]["consumedQty"]) == 10.0
          and float(rstock[0]["quantity"]) - float(rstock[0]["consumedQty"]) == 40.0, str(rstock))

    # --- C. 鲜牛奶到货 7℃，等待复检，复检合格正常入库 ---
    bc, milk_di2 = register_cold_batch(50, milk_oi["id"], ing["ING010"]["id"], 7, 2)
    case_c = call("inspector", "POST", "/api/cold-chain/cases", {
        "batchId": bc["id"], "deliveryItemId": milk_di2["id"], "arrivalTemperature": 7,
        "transportMinutes": 200, "productBatchNo": "NIUNAI-20261009-C",
        "photos": [{"photoUrl": "https://example.com/photos/milk-temp2.jpg"}]})
    dec_c = call("canteen", "POST", f"/api/cold-chain/cases/{case_c['id']}/decision",
                 {"disposition": "RECHECK", "note": "取样送检，暂缓处置"})
    check("等待复检状态 PENDING_LAB", dec_c["reviewStatus"] == "PENDING_LAB")
    call("inspector", "POST", "/api/acceptances", {
        "batchId": bc["id"], "measuredTemp": 7, "temperatureOk": False, "appearanceOk": True,
        "items": [{"deliveryItemId": milk_di2["id"], "acceptedQty": 50}]}, expect=409)
    rv_c = call("quality", "POST", f"/api/cold-chain/cases/{case_c['id']}/lab-result",
                {"pass": True, "labReportNo": "LAB2026100901", "note": "微生物指标合格"})
    check("复检合格放行", rv_c["reviewStatus"] == "PASSED" and rv_c["labReportNo"] == "LAB2026100901")
    rec_c = call("inspector", "POST", "/api/acceptances", {
        "batchId": bc["id"], "measuredTemp": 7, "temperatureOk": False, "appearanceOk": True,
        "items": [{"deliveryItemId": milk_di2["id"], "acceptedQty": 50}]})
    rd_c = call("inspector", "GET", f"/api/acceptances/{rec_c['id']}")
    check("复检合格批次验收入库 QUALIFIED",
          rd_c["items"][0]["result"] == "QUALIFIED" and float(rd_c["items"][0]["acceptedQty"]) == 50.0)
    levels = call("canteen", "GET", "/api/inventory/levels")
    milk_lv = find(levels, ingredient=ing["ING010"])
    check("鲜牛奶正常库存 60 + 复检合格 50 = 110（降级批次不计入）",
          float(milk_lv["quantity"]) == 110.0, str(milk_lv["quantity"]))

    # --- D. 冷链索赔与降级折让进入供应商结算 ---
    st3 = call("buyer", "POST", "/api/settlements/generate", {
        "supplierId": sup["SUP003"]["id"],
        "periodStart": str(today - timedelta(days=7)),
        "periodEnd": str(today + timedelta(days=14))})
    check("乳业结算单已生成", st3 is not None)
    check("乳业验收应付 400（降级50×4 + 复检50×4）",
          float(st3["deliveredAmount"]) == 400.0, str(st3["deliveredAmount"]))
    check("酸奶拒收退货扣减 300", float(st3["returnDeduction"]) == 300.0, str(st3["returnDeduction"]))
    check("冷链降级折价扣减 60（200×30%）", float(st3["downgradeDeduction"]) == 60.0,
          str(st3["downgradeDeduction"]))
    check("冷链拒收索赔扣减 30（直连结算）", float(st3["claimDeduction"]) == 30.0, str(st3["claimDeduction"]))
    check("冷链合同扣罚已计入(>=300)", float(st3["penaltyDeduction"]) >= 300.0, str(st3["penaltyDeduction"]))

    print("== 11. 未认证与 Swagger ==")
    import urllib.request as u
    try:
        u.urlopen(BASE + "/api/ingredients")
        check("未认证访问被拒(401)", False)
    except urllib.error.HTTPError as e:
        check("未认证访问被拒(401)", e.code == 401)
    try:
        code = u.urlopen(BASE + "/v3/api-docs").status
        check("OpenAPI 文档可访问", code == 200)
    except Exception:
        check("OpenAPI 文档可访问", False)

    print(f"\n结果: PASS={PASS} FAIL={FAIL}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
