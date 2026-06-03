# 人脸识别问题修复计划 V2

## 问题分析

### 问题1：注册员工时无法获取人脸数据
- **根本原因**: `FaceEnrollViewModel.init()` 是异步执行的，在 `faceEngine.init()` 尚未完成时就开始捕获帧
- `FaceEnrollScreen` 中的 `LaunchedEffect` 启动 ViewModel.init()，但相机预览可能先于引擎初始化完成
- 当 `faceEngine.isReady()` 返回 false 时，帧被立即丢弃

### 问题2：注视屏幕时无法触发人脸识别
- **根本原因1**: `FaceDetectionMiniWindow` 中的相机预览尺寸为 1x1，相机可能无法正常工作
- **根本原因2**: `FridgeHomeViewModel` 中的人脸检测逻辑存在多个限制条件
- **根本原因3**: 帧处理没有正确处理可能的失败情况

## 修复方案

### 文件修改列表

| 文件路径 | 修改内容 |
|---------|---------|
| `FaceEnrollViewModel.kt` | 确保引擎初始化完成后才开始捕获人脸 |
| `FaceEnrollScreen.kt` | 在引擎就绪前显示等待状态 |
| `FridgeHomeScreen.kt` | 增大人脸检测小窗口尺寸，确保相机正常工作 |
| `FridgeHomeViewModel.kt` | 优化人脸检测触发逻辑 |
| `FaceGateCameraPreview.kt` | 添加帧处理失败的重试机制 |

### 修复步骤

1. **修改 FaceEnrollViewModel.kt**
   - 添加 `isEngineReady` 状态跟踪
   - 提供回调通知引擎初始化完成
   - 确保只有引擎就绪时才处理帧

2. **修改 FaceEnrollScreen.kt**
   - 监听引擎就绪状态
   - 在引擎未就绪时显示等待提示，禁用相机预览

3. **修改 FridgeHomeScreen.kt**
   - 增大 `FaceDetectionMiniWindow` 的尺寸（从 1x1 改为 60x60）
   - 确保相机能够正常捕获帧

4. **修改 FridgeHomeViewModel.kt**
   - 优化人脸检测逻辑，减少不必要的限制
   - 添加更好的错误处理

5. **修改 FaceGateCameraPreview.kt**
   - 添加帧转换失败的日志记录
   - 确保 `onFrame` 回调正确处理所有情况

## 风险评估

- **低风险**: 修复涉及状态管理和UI调整，不影响核心算法
- **影响范围**: 人脸注册、人脸登录、主页人脸检测
- **回归测试**: 需要验证三个场景的功能正常

## 测试验证

修复完成后，需要验证：
1. 人脸注册流程能够正常捕获人脸数据
2. 注视屏幕时能够触发人脸识别弹窗
3. 人脸识别登录能够正常工作