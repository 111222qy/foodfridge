# 人脸识别自动触发问题修复计划

## 问题分析

用户反馈注视屏幕时不能自动触发人脸识别。通过分析代码，发现以下问题：

**1. 状态更新时序问题**

* `LaunchedEffect(latestFrameVersion)` 中使用 `latestUiState` 检查 `isRecognizing`

* 但 `latestUiState` 可能不是最新的状态，导致条件判断不准确

**2. 条件判断逻辑问题**

* `hasFrame` 的判断可能在帧更新后未及时反映

* `LaunchedEffect` 的触发时机与状态更新不同步

**3. 防抖机制过于严格**

* `FACE_GATE_AUTO_SCAN_INTERVAL_MS = 750L` 可能导致识别触发不及时

## 修复方案

### 方案一：简化自动触发逻辑

移除复杂的条件判断，直接在帧回调中触发识别（带防抖）

### 方案二：优化状态同步

确保 `LaunchedEffect` 能够正确获取最新状态

## 修改文件

| 文件                                                                           | 修改内容               |
| ---------------------------------------------------------------------------- | ------------------ |
| `app/src/main/java/com/foodfridge/ui/facecheck/FaceRecognitionGateScreen.kt` | 简化自动触发逻辑，在帧回调中直接触发 |

## 修复步骤

1. **修改** **`FaceRecognitionGateScreen.kt`**：

   * 在 `onFrame` 回调中直接调用 `viewModel.verifyAndContinue()`

   * 移除 `LaunchedEffect(latestFrameVersion)` 中的触发逻辑

   * 保留防抖机制

## 预期结果

修复后，当摄像头捕获到帧时，如果不在识别中，会自动触发人脸识别。

## 风险评估

| 风险   | 等级 | 描述                | 缓解措施                            |
| ---- | -- | ----------------- | ------------------------------- |
| 性能影响 | 低  | 频繁触发识别可能增加 CPU 负载 | 保留防抖机制控制频率                      |
| 重复识别 | 低  | 可能导致重复请求          | ViewModel 已有 `isRecognizing` 检查 |

