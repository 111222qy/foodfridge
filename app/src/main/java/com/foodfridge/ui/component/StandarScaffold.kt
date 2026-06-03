package com.foodfridge.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack // [修改] 换回基础图标库
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val BACK_CLICK_DEBOUNCE_MS = 260L

@Composable
fun RoundedBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onPrimary,
    containerColor: Color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
) {
    var lastBackClickAtMs by remember { mutableStateOf(0L) }

    Surface(
        modifier = modifier.size(40.dp),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
    ) {
        IconButton(
            onClick = {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackClickAtMs < BACK_CLICK_DEBOUNCE_MS) return@IconButton
                lastBackClickAtMs = now
                onClick()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "返回",
                tint = iconTint,
            )
        }
    }
}

/**
 * 通用页面框架
 * @param title 页面标题
 * @param onBackClick 返回键点击事件。如果传 null，则不显示返回键。
 * @param content 页面内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardScaffold(
    title: String,
    onBackClick: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    ) 
                },
                navigationIcon = {
                    // 只有当传入了 onBackClick 时才显示返回箭头
                    if (onBackClick != null) {
                        RoundedBackButton(onClick = onBackClick)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // 使用 Box 确保内容填满剩余空间
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            content(paddingValues)
        }
    }
}