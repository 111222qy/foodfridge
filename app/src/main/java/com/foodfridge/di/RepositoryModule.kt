package com.foodfridge.di

import com.foodfridge.data.repository.FoodSampleRepositoryImpl
import com.foodfridge.data.repository.TemperatureRepositoryImpl
import com.foodfridge.data.repository.UserRepositoryImpl
import com.foodfridge.domain.repository.FoodSampleRepository
import com.foodfridge.domain.repository.TemperatureRepository
import com.foodfridge.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUserRepository(
        userRepositoryImpl: UserRepositoryImpl
    ): UserRepository

    @Binds
    @Singleton
    abstract fun bindFoodSampleRepository(
        foodSampleRepositoryImpl: FoodSampleRepositoryImpl
    ): FoodSampleRepository

    @Binds
    @Singleton
    abstract fun bindTemperatureRepository(
        temperatureRepositoryImpl: TemperatureRepositoryImpl
    ): TemperatureRepository
}
