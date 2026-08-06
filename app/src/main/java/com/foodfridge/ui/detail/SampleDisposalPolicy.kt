package com.foodfridge.ui.detail

import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.SampleStatus

internal object SampleDisposalPolicy {
    const val RETENTION_DURATION_MS = 48L * 60 * 60 * 1000

    fun requiresAdmin(samples: List<FoodSample>, now: Long): Boolean {
        return samples.any { sample ->
            sample.status == SampleStatus.STORING &&
                now < sample.storeTime + RETENTION_DURATION_MS
        }
    }
}
