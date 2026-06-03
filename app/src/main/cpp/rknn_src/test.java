package com.dongbei.weight.algorithm;

import android.graphics.Bitmap;
import java.util.ArrayList;

public class Algorithm {
    // Native方法声明
    public static native boolean initModels(String yoloModelPath, String mobilenetModelPath);
    public static native boolean detect(Bitmap bitmap, String targetLabel, String dbPath, ArrayList<ClassNameInfo> resultList);
    public static native boolean releaseModels();

    static {
        System.loadLibrary("yolov6_jni");
    }
}

// 检测结果数据类（字段需与JNI操作完全一致）
public class ClassNameInfo {
    public String cls_name;  // 必须与JNI字段名一致
    public float score;      // 基本类型float对应JNI的"F"签名
    public int left;
    public int top;
    public int right;
    public int bottom;

    // 空构造函数（JNI需要）
    public ClassNameInfo() {}
}

// 使用示例
public class MainActivity extends AppCompatActivity {
    void runDetection() {
        // 初始化模型
        String yoloPath = "/sdcard/models/yolov6.rknn";
        String mobilenetPath = "/sdcard/models/mobilenet.rknn";
        boolean initSuccess = Algorithm.initModels(yoloPath, mobilenetPath);

        if (initSuccess) {
            Bitmap inputBitmap = getInputBitmap(); // 获取输入位图
            String targetLabel = "person";
            ArrayList<ClassNameInfo> results = new ArrayList<>();
            String dbPath = "/sdcard/feature.db";

            // 执行检测
            boolean detectSuccess = Algorithm.detect(
                inputBitmap, 
                targetLabel,
                dbPath,  // 新增数据库路径参数
                results
            );

            // 处理结果
            if (detectSuccess) {
                for (ClassNameInfo info : results) {
                    Log.d("Detection", String.format(
                        "%s [%.2f] @(%d,%d,%d,%d)", 
                        info.cls_name, 
                        info.score,
                        info.left,
                        info.top,
                        info.right,
                        info.bottom
                    ));
                }
            }

            // 释放资源
            Algorithm.releaseModels();
        }
    }

    private Bitmap getInputBitmap() {
        // 实现获取位图的逻辑
        return null; 
    }
}

public class InferInfo {
    public DetectInfo box;
    public ArrayList<ClassifyInfo> classifyInfos;

    public InferInfo() {
    }
}


public class DetectInfo {
    public int x1;
    public int y1;
    public int x2;
    public int y2;
    public int c;
    public DetectInfo() {
    }
}


public class ClassifyInfo {
    public String className;
    public float score;
    public ClassifyInfo() {
    }
}