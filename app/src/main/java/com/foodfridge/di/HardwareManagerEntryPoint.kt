package com.foodfridge.di

import com.foodfridge.data.hardware.HardwareManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 用于在无法使用构造函数注入的地方（如 [android.app.Application]）获取 [HardwareManager]。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HardwareManagerEntryPoint {
    fun hardwareManager(): HardwareManager
}
