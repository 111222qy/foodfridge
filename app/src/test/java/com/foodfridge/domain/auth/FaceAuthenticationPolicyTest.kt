package com.foodfridge.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAuthenticationPolicyTest {
    @Test
    fun `single face mode only allows sampler to unlock`() {
        assertTrue(
            FaceAuthenticationPolicy.shouldUnlock(
                dualFaceEnabled = false,
                authenticatedRoles = emptyList(),
                candidateRole = FaceAuthenticationPolicy.ROLE_SAMPLER,
            )
        )
        assertFalse(
            FaceAuthenticationPolicy.shouldUnlock(
                dualFaceEnabled = false,
                authenticatedRoles = emptyList(),
                candidateRole = FaceAuthenticationPolicy.ROLE_SUPERVISOR,
            )
        )
    }

    @Test
    fun `first face never unlocks in dual mode`() {
        assertFalse(
            FaceAuthenticationPolicy.shouldUnlock(
                dualFaceEnabled = true,
                authenticatedRoles = emptyList(),
                candidateRole = FaceAuthenticationPolicy.ROLE_SAMPLER,
            )
        )
        assertFalse(
            FaceAuthenticationPolicy.shouldUnlock(
                dualFaceEnabled = true,
                authenticatedRoles = emptyList(),
                candidateRole = FaceAuthenticationPolicy.ROLE_SUPERVISOR,
            )
        )
    }

    @Test
    fun `second face must have complementary role`() {
        assertEquals(
            setOf(FaceAuthenticationPolicy.ROLE_SUPERVISOR),
            FaceAuthenticationPolicy.allowedRoles(
                dualFaceEnabled = true,
                authenticatedRoles = listOf(FaceAuthenticationPolicy.ROLE_SAMPLER),
            )
        )
        assertTrue(
            FaceAuthenticationPolicy.shouldUnlock(
                dualFaceEnabled = true,
                authenticatedRoles = listOf(FaceAuthenticationPolicy.ROLE_SAMPLER),
                candidateRole = FaceAuthenticationPolicy.ROLE_SUPERVISOR,
            )
        )
        assertFalse(
            FaceAuthenticationPolicy.shouldUnlock(
                dualFaceEnabled = true,
                authenticatedRoles = listOf(FaceAuthenticationPolicy.ROLE_SAMPLER),
                candidateRole = FaceAuthenticationPolicy.ROLE_SAMPLER,
            )
        )
    }
}
