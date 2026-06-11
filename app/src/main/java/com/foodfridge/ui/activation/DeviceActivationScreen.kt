package com.foodfridge.ui.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DeviceActivationScreen(
    onActivationSuccess: () -> Unit,
    viewModel: DeviceActivationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isActivated) {
        onActivationSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F8FB))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "设备激活",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = uiState.message,
            fontSize = 16.sp,
            color = Color(0xFF64748B),
        )

        Spacer(modifier = Modifier.height(32.dp))

        uiState.deviceInfo?.let { info ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoRow(label = "设备编号", value = info.deviceNumber)
                    InfoRow(label = "MAC地址", value = info.deviceMac)
                    InfoRow(label = "激活码", value = info.activationCode)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.errorMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFEE2E2),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = Color(0xFFB91C1C),
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = { viewModel.activateDevice() },
            enabled = !uiState.isLoading && !uiState.isActivated,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (uiState.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(24.dp),
                    )
                    Text("激活中...", color = Color.White, fontSize = 16.sp)
                }
            } else {
                Text(
                    text = if (uiState.isActivated) "已激活" else "立即激活",
                    color = Color.White,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = Color(0xFF64748B),
            fontSize = 14.sp,
        )
        Text(
            text = value,
            color = Color(0xFF0F172A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
