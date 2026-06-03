# 应用崩溃修复计划

## 问题分析

通过对代码库的全面分析，发现以下可能导致崩溃的关键问题：

### 1. FaceGateCameraPreview.kt 问题
- **问题1**: 相机资源管理不完整，可能导致重复初始化
- **问题2**: Bitmap 内存管理不当，可能导致 OOM
- **问题3**: 缺少完整的生命周期监听，资源释放不彻底
- **问题4**: convertToBitmap 函数中缺少严格的空检查和边界检查

### 2. FaceRecognitionGateScreen.kt 问题
- **问题1**: 每帧都调用 verifyAndContinue，可能导致处理队列过载
- **问题2**: Bitmap 双重释放风险
- **问题3**: 缺少帧处理速率限制

### 3. FaceRecognitionGateViewModel.kt 问题
- **问题1**: 缺少对 Bitmap 资源的全面异常处理

## 修复方案

### 修复1: FaceGateCameraPreview.kt
1. 使用 DisposableEffect 替代 LaunchedEffect 进行相机生命周期管理
2. 实现完整的相机资源清理
3. 增强 convertToBitmap 的错误处理
4. 添加帧丢弃机制防止内存溢出
5. 使用 CameraX 的正确方式进行资源绑定

### 修复2: FaceRecognitionGateScreen.kt
1. 添加帧处理速率限制（例如每秒最多处理3-5帧）
2. 优化 Bitmap 资源管理
3. 添加防抖机制

### 修复3: FaceRecognitionGateViewModel.kt
1. 增强异常处理
2. 优化协程取消处理

## 修改文件清单
1. `s:\foodfridge\app\src\main\java\com\foodfridge\ui\facecheck\FaceGateCameraPreview.kt`
2. `s:\foodfridge\app\src\main\java\com\foodfridge\ui\facecheck\FaceRecognitionGateScreen.kt`
3. `s:\foodfridge\app\src\main\java\com\foodfridge\ui\facecheck\FaceRecognitionGateViewModel.kt`
