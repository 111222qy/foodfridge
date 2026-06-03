# 相机预览崩溃问题修复计划

## 问题分析

应用在运行几秒钟后自行退出，可能的原因：

1. **相机重复初始化** - `LaunchedEffect(hasPermission, enabled, cameraProvider)` 每次状态变化都会触发，导致多次调用 `initializeCamera`
2. **线程池资源泄漏** - `Executors.newSingleThreadExecutor()` 创建的线程池没有在组件销毁时关闭
3. **缺少 DisposableEffect 清理** - 没有在组件销毁时解绑相机和释放资源
4. **Bitmap 内存压力** - 每帧都创建 Bitmap 但可能没有及时回收

## 修复方案

### 1. 使用 DisposableEffect 管理相机生命周期
- 在组件创建时初始化相机
- 在组件销毁时解绑相机并关闭线程池

### 2. 添加初始化标志位
- 使用 `isInitialized` 标志防止重复初始化

### 3. 优化 Bitmap 处理
- 确保每帧的 Bitmap 都被正确处理

## 修改文件

**`s:\foodfridge\app\src\main\java\com\foodfridge\ui\facecheck\FaceGateCameraPreview.kt`**

### 修改步骤

1. 添加 `DisposableEffect` 用于资源清理
2. 添加初始化标志位 `isCameraInitialized`
3. 将相机初始化逻辑移到 `DisposableEffect` 中
4. 在 `onDispose` 中解绑相机并关闭线程池