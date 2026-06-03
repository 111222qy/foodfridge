# 三天留样显示优化计划

## 需求理解

当前首页显示两天的留样记录："今天"和"昨天"。

客户新需求：

1. 将显示天数从两天改为三天（第一天、第二天、第三天）
2. 第三天结束时，当第一天的晚餐从"已留样"改为"已消样"的操作完成后，自动执行日期滚动：

   * 第一天的记录 → 变为第二天

   * 第二天的记录 → 变为第三天

   * 第三天 → 新增一天（空记录）

## 核心设计思路

### 1. 数据层设计（不修改数据库结构）

数据库中的 `created_at` 字段保持不变，**不**实际修改记录的创建时间。

引入一个\*\*逻辑偏移量（dayOffset）\*\*的概念：

* 第一天 = 偏移量 0（最旧的、即将滚出的一天）

* 第二天 = 偏移量 1

* 第三天 = 偏移量 2（最新的一天）

显示时通过逻辑偏移量来查询和展示数据，而不是固定的"今天/昨天"。

### 2. 滚动触发机制

当第一天的晚餐（DINNER）状态从 `STORING`（已留样）变为 `WAITING_DISPOSE`（已消样/待消样）时，触发日期滚动。

具体判断逻辑：

* 检测到某条 DINNER 记录的状态从 STORING 被更新为 WAITING\_DISPOSE

* 且该记录属于当前显示的第一天（created\_at 在第一天的时间范围内）

* 触发 `rollDayOffsets()` 方法

### 3. 日期滚动的实现

由于不修改数据库中的 created\_at，滚动通过调整**查询的时间窗口**来实现。

在 ViewModel 中维护一个 `baseDate`（基准日期），初始值为当天 00:00。

* 第一天查询范围：`baseDate - 2天` 到 `baseDate - 1天`

* 第二天查询范围：`baseDate - 1天` 到 `baseDate`

* 第三天查询范围：`baseDate` 到 `baseDate + 1天`

当触发滚动时，`baseDate` 增加 1 天。这样：

* 原来的第一天（baseDate-2 到 baseDate-1）被移出显示范围

* 原来的第二天变为第一天

* 原来的第三天变为第二天

* 新增的一天（baseDate+1 到 baseDate+2）成为第三天

### 4. UI 层修改

FridgeHomeScreen.kt：

* 将左右两列改为三列并排显示

* 标题从 "今天"/"昨天" 改为 "第一天"/"第二天"/"第三天"

* 每个列对应不同的 dayOffset（0, 1, 2）

* 点击跳转时传递正确的 dayOffset

### 5. 状态流转图

```
初始状态（假设今天是6月3日）：
  第一天(6/1)  第二天(6/2)  第三天(6/3)

当第一天晚餐被消样后，触发滚动：
  第一天(6/2)  第二天(6/3)  第三天(6/4)
```

## 实施步骤

### 步骤 1：修改 FridgeHomeUiState 和 MealCardState

* 将 `todayCards` 和 `yesterdayCards` 改为 `day1Cards`、`day2Cards`、`day3Cards`

* 每个卡片列表对应一个逻辑日期偏移

### 步骤 2：修改 FridgeHomeViewModel

* 添加 `baseDate` 字段，初始化为当天 00:00

* 修改 `loadMealStates()`，根据 baseDate 查询三天的数据

* 添加 `rollDayOffsets()` 方法，在检测到第一天晚餐消样时调用，将 baseDate 增加 1 天

* 修改 `startExpiryCheck()`，在更新状态时检测是否是第一天晚餐的消样操作，如果是则触发滚动

* 添加三个 update 方法分别更新三天的卡片状态

### 步骤 3：修改 FridgeHomeScreen.kt UI

* 将 Row 中的两列改为三列

* 列标题改为 "第一天"、"第二天"、"第三天"

* 显示对应的日期字符串

* 每个列绑定到对应的 cards 列表

* 点击时传递正确的 dayOffset（0, 1, 2 分别对应第一天、第二天、第三天）

### 步骤 4：修改 SampleTableScreen.kt 和 SampleTableViewModel.kt

* `dayLabel` 逻辑支持 dayOffset 为 0, 1, 2 的情况

* 显示 "第一天"、"第二天"、"第三天" 而不是 "今天"/"昨天"

### 步骤 5：验证和测试

* 编译检查

* 逻辑验证：确认日期滚动时各列数据正确切换

## 文件修改清单

1. `app/src/main/java/com/foodfridge/ui/home/FridgeHomeViewModel.kt` - 核心逻辑修改
2. `app/src/main/java/com/foodfridge/ui/home/FridgeHomeScreen.kt` - UI 布局修改
3. `app/src/main/java/com/foodfridge/ui/table/SampleTableScreen.kt` - 标签显示修改
4. `app/src/main/java/com/foodfridge/ui/table/SampleTableViewModel.kt` - 日期查询逻辑修改

