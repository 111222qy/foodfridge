package com.foodfridge.domain.repository

import com.foodfridge.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getAllUsers(): Flow<List<User>>
    
    suspend fun getUserById(userId: Int): User?
    
    suspend fun getUserByEmployeeId(employeeId: String): User?

    suspend fun getUserByFullName(fullName: String): User?

    suspend fun insertUser(user: User)
    
    suspend fun updateUser(user: User)
    
    suspend fun deleteUserById(userId: Int)
}
