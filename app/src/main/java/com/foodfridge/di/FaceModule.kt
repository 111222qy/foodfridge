package com.foodfridge.di

import com.foodfridge.data.face.SeetaFaceEngine
import com.foodfridge.domain.face.FaceEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FaceModule {

    @Binds
    @Singleton
    abstract fun bindFaceEngine(
        impl: SeetaFaceEngine,
    ): FaceEngine
}
