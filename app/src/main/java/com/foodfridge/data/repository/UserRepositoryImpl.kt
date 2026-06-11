package com.foodfridge.data.repository

import com.foodfridge.data.local.dao.UserDao
import com.foodfridge.data.local.entity.UserEntity
import com.foodfridge.domain.model.User
import com.foodfridge.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao
) : UserRepository {

    override fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getUserById(userId: Int): User? {
        return userDao.getUserById(userId)?.toDomain()
    }

    override suspend fun getUserByEmployeeId(employeeId: String): User? {
        return userDao.getUserByEmployeeId(employeeId)?.toDomain()
    }

    override suspend fun getUserByFullName(fullName: String): User? {
        return userDao.getUserByFullName(fullName)?.toDomain()
    }

    override suspend fun insertUser(user: User): Long {
        return userDao.insertUser(user.toEntity())
    }

    override suspend fun updateUser(user: User) {
        userDao.updateUser(user.toEntity())
    }

    // 【关键修复】这里必须是 deleteUserById，且参数是 Int
    // 之前写成了 deleteUser(user: User) 导致了报错
    override suspend fun deleteUserById(userId: Int) {
        userDao.deleteUserById(userId)
    }

    // ==========================================
    // Mapper 映射函数
    // ==========================================

    private fun UserEntity.toDomain(): User {
        return User(
            id = id,
            fullName = fullName,
            employeeId = employeeId,
            role = role,
            isActive = isActive,
            password = password,
            faceEmbedding = faceEmbedding,
            facePhotoPath = facePhotoPath,
        )
    }

    private fun User.toEntity(): UserEntity {
        return UserEntity(
            id = id,
            fullName = fullName,
            employeeId = employeeId,
            role = role,
            isActive = isActive,
            password = password,
            faceEmbedding = faceEmbedding,
            facePhotoPath = facePhotoPath,
        )
    }
}