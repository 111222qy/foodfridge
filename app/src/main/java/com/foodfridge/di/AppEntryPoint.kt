package com.foodfridge.di

import com.foodfridge.data.hardware.HardwareManager
import com.foodfridge.data.local.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 用于在无法使用构造函数注入的地方（如 [android.app.Application]）获取应用级依赖。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun hardwareManager(): HardwareManager
    fun userPreferencesRepository(): UserPreferencesRepository
}
