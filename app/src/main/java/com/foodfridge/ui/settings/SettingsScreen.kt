package com.foodfridge.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodfridge.domain.model.User

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFaceEnroll: (Int) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<User?>(null) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var previewPhotoPath by remember { mutableStateOf<String?>(null) }
    var isDeleteMode by remember { mutableStateOf(false) }
    var selectedUserIds by remember { mutableStateOf(setOf<Int>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadUsers()
        viewModel.loadAdminPassword()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 顶部返回 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = Color(0xFF111827),
                )
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "设置",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 留样员信息
        InfoRow(label = "留样员：", value = uiState.currentUserName ?: "admin")

        Spacer(modifier = Modifier.height(12.dp))

        // 密码 + 修改按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "密码：",
                fontSize = 14.sp,
                color = Color(0xFF374151),
            )
            Text(
                text = "******",
                fontSize = 14.sp,
                color = Color(0xFF111827),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { showChangePasswordDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                ),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    text = "修改",
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 留样员标签 + 删除按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isDeleteMode) "请选择要删除的员工" else "留样员",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDeleteMode) Color(0xFFDC2626) else Color(0xFF374151),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!isDeleteMode && uiState.users.isNotEmpty()) {
                Button(
                    onClick = {
                        isDeleteMode = true
                        selectedUserIds = emptySet()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                    ),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(
                        text = "删除",
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 留样员照片列表
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 已有人员
            items(uiState.users) { user ->
                PersonAvatar(
                    name = user.fullName,
                    facePhotoPath = user.facePhotoPath,
                    isSelected = selectedUserIds.contains(user.id),
                    isDeleteMode = isDeleteMode,
                    onClick = {
                        if (isDeleteMode) {
                            selectedUserIds = if (selectedUserIds.contains(user.id)) {
                                selectedUserIds - user.id
                            } else {
                                selectedUserIds + user.id
                            }
                        } else if (user.facePhotoPath != null) {
                            previewPhotoPath = user.facePhotoPath
                        } else {
                            onNavigateToFaceEnroll(user.id)
                        }
                    },
                    onLongClick = {
                        if (!isDeleteMode) {
                            onNavigateToFaceEnroll(user.id)
                        }
                    },
                )
            }
            // 添加按钮（删除模式下隐藏）
            if (!isDeleteMode) {
                item {
                    AddPersonButton(onClick = { showAddDialog = true })
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 双人脸模式开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "是否开启双人脸模式",
                fontSize = 14.sp,
                color = Color(0xFF374151),
            )
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = uiState.dualFaceAuthEnabled,
                onCheckedChange = { viewModel.toggleDualFaceAuth(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2563EB),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD1D5DB),
                ),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 平台 API 地址配置 ──────────────────────────────────
        Text(
            text = "平台连接配置",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF374151),
        )

        Spacer(modifier = Modifier.height(8.dp))

        var apiUrl by remember { mutableStateOf(uiState.apiBaseUrl) }

        LaunchedEffect(uiState.apiBaseUrl) {
            apiUrl = uiState.apiBaseUrl
        }

        OutlinedTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it },
            label = { Text("平台 API 地址（如 http://192.168.1.100:8000）") },
            placeholder = { Text("留空使用默认地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2563EB),
                focusedLabelColor = Color(0xFF2563EB),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.saveApiBaseUrl(apiUrl) },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2563EB),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存 API 地址")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.debugApiConnection() },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("测试接口") }

        uiState.apiDebugResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                fontSize = 12.sp,
                color = if (result.startsWith("✅")) Color(0xFF059669) else Color(0xFFDC2626),
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 删除模式底部操作栏
        if (isDeleteMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        isDeleteMode = false
                        selectedUserIds = emptySet()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9CA3AF),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { showBatchDeleteConfirm = true },
                    enabled = selectedUserIds.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        disabledContainerColor = Color(0xFFFCA5A5),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("确认删除(${selectedUserIds.size})")
                }
            }
        }
    }

    // 添加用户对话框
    if (showAddDialog) {
        AddUserDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, employeeId, role ->
                viewModel.addUser(name, employeeId, role)
                showAddDialog = false
            },
            onEnrollFace = { name, employeeId, role ->
                showAddDialog = false
                viewModel.addUserAndGetId(name, employeeId, role) { userId ->
                    onNavigateToFaceEnroll(userId)
                }
            },
        )
    }

    // 修改密码对话框
    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { newPassword ->
                viewModel.changeAdminPassword(newPassword)
                showChangePasswordDialog = false
            },
        )
    }

    // 图片预览对话框
    previewPhotoPath?.let { path ->
        ImagePreviewDialog(
            photoPath = path,
            onDismiss = { previewPhotoPath = null },
        )
    }

    // 批量删除确认对话框
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("确认批量删除") },
            text = { Text("确定要删除选中的 ${selectedUserIds.size} 位员工吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteUsers(selectedUserIds)
                        isDeleteMode = false
                        selectedUserIds = emptySet()
                        showBatchDeleteConfirm = false
                    },
                ) {
                    Text("删除", color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 删除确认对话框
    showDeleteConfirm?.let { user ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除用户「${user.fullName}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteUser(user.id)
                        showDeleteConfirm = null
                    },
                ) {
                    Text("删除", color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFF374151),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = Color(0xFF111827),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonAvatar(
    name: String,
    facePhotoPath: String?,
    isSelected: Boolean = false,
    isDeleteMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (isDeleteMode) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
        },
    ) {
        // 选择复选框（删除模式下显示）
        if (isDeleteMode) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isSelected) Color(0xFF2563EB) else Color(0xFFD1D5DB)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选中",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        // 头像占位
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFE5E7EB))
                .then(
                    if (isSelected && isDeleteMode) {
                        Modifier.border(2.dp, Color(0xFFDC2626), RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (facePhotoPath != null) {
                val bitmap = remember(facePhotoPath) {
                    BitmapFactory.decodeFile(facePhotoPath)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = name.take(1),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B7280),
                    )
                }
            } else {
                Text(
                    text = name.take(1),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6B7280),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            fontSize = 12.sp,
            color = Color(0xFF374151),
        )
    }
}

@Composable
private fun AddPersonButton(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(
                    BorderStroke(1.dp, Color(0xFFD1D5DB)),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加",
                modifier = Modifier.size(28.dp),
                tint = Color(0xFF2563EB),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "",
            fontSize = 12.sp,
            color = Color.Transparent,
        )
    }
}

@Composable
private fun AddUserDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onEnrollFace: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("SAMPLER") }
    val roles = listOf(
        "SUPERVISOR" to "监督员",
        "SAMPLER" to "留样员",
    )
    val isFormValid = name.isNotBlank() && employeeId.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加人员") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = { Text("工号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    text = "角色",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF374151),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    roles.forEach { (roleValue, roleLabel) ->
                        val isSelected = selectedRole == roleValue
                        val bgColor = if (isSelected) Color(0xFF2563EB) else Color(0xFFF3F4F6)
                        val textColor = if (isSelected) Color.White else Color(0xFF6B7280)
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = bgColor,
                            onClick = { selectedRole = roleValue },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    text = roleLabel,
                                    color = textColor,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
                // 注册人脸按钮
                Button(
                    onClick = {
                        if (isFormValid) {
                            onEnrollFace(name, employeeId, selectedRole)
                        }
                    },
                    enabled = isFormValid,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                    ),
                ) {
                    Text("注册人脸")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isFormValid) {
                        onConfirm(name, employeeId, selectedRole)
                    }
                },
                enabled = isFormValid,
            ) {
                Text("仅保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前使用的是默认密码，为了安全请修改密码",
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        errorMessage = null
                    },
                    label = { Text("新密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text("确认新密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFDC2626),
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        newPassword.isBlank() -> errorMessage = "密码不能为空"
                        newPassword.length < 4 -> errorMessage = "密码至少4位"
                        newPassword != confirmPassword -> errorMessage = "两次输入的密码不一致"
                        else -> onConfirm(newPassword)
                    }
                },
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后再说")
            }
        },
    )
}

@Composable
private fun ImagePreviewDialog(
    photoPath: String,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(photoPath) {
        BitmapFactory.decodeFile(photoPath)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人脸照片预览") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "人脸照片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "无法加载图片",
                        color = Color(0xFFDC2626),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
