package com.foodfridge.data.face.ipc;

import android.graphics.Bitmap;
import android.os.Bundle;
import java.util.List;

interface IRemoteFaceService {
    void init();
    Bundle detectAndRecognize(in Bitmap frame);
    boolean registerUser(int userId, in List<Bitmap> frames);
    void refreshUserCache();
    void release();
}
