# 人脸识别问题修复计划

## 问题分析

### 问题1：注册员工时无法获取人脸数据
- **位置**: `FaceEnrollScreen.kt` 和 `FaceEnrollViewModel.kt`
- **原因**: 
  - `FaceEnrollScreen` 在调用 `viewModel.captureFrame(frame)` 后立即调用 `frame.recycle()`
  - `FaceEnrollViewModel.captureFrame()` 内部尝试通过 `frame.copy()` 复制帧
  - 由于帧已被外部回收，`copy()` 返回 null，导致无法获取人脸数据

### 问题2：注视屏幕时无法触发人脸识别
- **位置**: `FaceRecognitionGateScreen.kt` 和 `FaceRecognitionGateViewModel.kt`
- **原因**:
  - `FaceRecognitionGateScreen` 在调用 `viewModel.verifyAndContinue(frame, isAutoScan = true)` 后立即回收帧
  - ViewModel 内部处理时帧已无效，导致人脸识别无法正常工作

## 修复方案

### 文件修改列表

| 文件路径 | 修改内容 |
|---------|---------|
| `app/src/main/java/com/foodfridge/ui/settings/FaceEnrollScreen.kt` | 移除 frame.recycle() 调用，让 ViewModel 负责帧生命周期 |
| `app/src/main/java/com/foodfridge/ui/settings/FaceEnrollViewModel.kt` | 确保在所有代码路径中正确处理帧回收 |
| `app/src/main/java/com/foodfridge/ui/facecheck/FaceRecognitionGateScreen.kt` | 移除 frame.recycle() 调用 |
| `app/src/main/java/com/foodfridge/ui/facecheck/FaceRecognitionGateViewModel.kt` | 确保在所有代码路径中正确处理帧回收 |

### 修复步骤

1. **修改 FaceEnrollScreen.kt**
   - 删除 `FaceGateCameraPreview` 的 `onFrame` 回调中的 `frame.recycle()` 调用

2. **修改 FaceEnrollViewModel.kt**
   - 在 `captureFrame()` 方法中，确保在所有返回路径中正确回收帧
   - 当不需要帧时（isRegistering, success, !faceEngine.isReady()），立即回收帧
   - 当复制成功时，回收原始帧，保留副本

3. **修改 FaceRecognitionGateScreen.kt**
   - 删除 `FaceGateCameraPreview` 的 `onFrame` 回调中的 `frame.recycle()` 调用

4. **修改 FaceRecognitionGateViewModel.kt**
   - 在 `verifyAndContinue()` 方法中，确保在所有返回路径中正确回收帧

## 风险评估

- **低风险**: 修复仅涉及帧生命周期管理，不影响人脸识别核心算法
- **影响范围**: 人脸注册和人脸登录两个功能模块
- **回归测试**: 需要验证人脸注册和人脸登录功能是否正常工作

## 测试验证

修复完成后，需要验证：
1. 人脸注册流程能够正常捕获人脸数据并完成注册
2. 人脸登录流程能够实时检测并识别人脸
3. 帧回收机制正确，无内存泄漏