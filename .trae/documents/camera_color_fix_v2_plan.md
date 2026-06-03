# 摄像头色彩问题修复计划（V2）

## 问题分析

用户反馈人脸识别时摄像头显示黑白而非彩色。之前的修复尝试添加了 `setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)`，但问题仍未解决。

**可能的根本原因：**
1. 某些设备/相机不支持 RGBA_8888 格式，CameraX 会自动回退到默认的 YUV_420_888 格式
2. YUV_420_888 转 RGB 的转换逻辑可能存在问题
3. `FaceRecognitionGateScreen.kt` 中存在重复的相机配置代码，未同步修改

## 修复方案

### 方案一：增强 YUV_420_888 格式处理（推荐）
即使设置了 RGBA_8888，部分设备仍会返回 YUV 格式。需要确保 YUV 转 RGB 的转换正确。

### 方案二：使用 Camera2 扩展配置
通过 Camera2 互操作 API 强制设置输出格式。

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `app/src/main/java/com/foodfridge/ui/facecheck/FaceGateCameraPreview.kt` | 增强 YUV_420_888 转换逻辑，确保正确的色彩转换 |
| `app/src/main/java/com/foodfridge/ui/facecheck/FaceRecognitionGateScreen.kt` | 添加 OUTPUT_IMAGE_FORMAT_RGBA_8888 配置 |

## 修复步骤

1. **修改 `FaceGateCameraPreview.kt`**：
   - 优化 `imageProxyToNv21` 函数，确保正确的色彩通道顺序
   - 添加对不同 YUV 布局的兼容性处理

2. **修改 `FaceRecognitionGateScreen.kt`**：
   - 在 ImageAnalysis.Builder 中添加 `setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)`

## 关键修改点

### 1. YUV_420_888 转 NV21 的正确实现
确保 U/V 通道的正确提取和交错写入。

### 2. 添加格式降级处理
当 RGBA_8888 不可用时，确保 YUV 转换能正确处理色彩。

## 风险评估

| 风险 | 等级 | 描述 | 缓解措施 |
|------|------|------|----------|
| 格式兼容性 | 中 | 某些设备可能有特殊的 YUV 布局 | 添加多种 YUV 布局的处理逻辑 |
| 性能影响 | 低 | YUV 转 RGB 可能增加 CPU 负载 | 使用高效的转换算法 |

## 预期结果

修复后，摄像头预览和分析帧都应该正确显示彩色画面。
