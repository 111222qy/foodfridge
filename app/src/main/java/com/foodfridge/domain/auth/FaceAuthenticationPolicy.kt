package com.foodfridge.domain.auth

object FaceAuthenticationPolicy {
    const val ROLE_SAMPLER = "SAMPLER"
    const val ROLE_SUPERVISOR = "SUPERVISOR"
    const val ROLE_ADMIN = "ADMIN"

    fun allowedRoles(
        dualFaceEnabled: Boolean,
        authenticatedRoles: List<String>,
    ): Set<String> {
        if (!dualFaceEnabled) return setOf(ROLE_SAMPLER)
        return when (authenticatedRoles.singleOrNull()) {
            null -> if (authenticatedRoles.isEmpty()) {
                setOf(ROLE_SAMPLER, ROLE_SUPERVISOR)
            } else {
                emptySet()
            }
            ROLE_SAMPLER -> setOf(ROLE_SUPERVISOR)
            ROLE_SUPERVISOR -> setOf(ROLE_SAMPLER)
            else -> emptySet()
        }
    }

    fun shouldUnlock(
        dualFaceEnabled: Boolean,
        authenticatedRoles: List<String>,
        candidateRole: String,
    ): Boolean {
        if (candidateRole !in allowedRoles(dualFaceEnabled, authenticatedRoles)) return false
        return !dualFaceEnabled || authenticatedRoles.size == 1
    }

    fun displayName(role: String): String = when (role) {
        ROLE_SAMPLER -> "留样员"
        ROLE_SUPERVISOR -> "监督员"
        ROLE_ADMIN -> "管理员"
        else -> role
    }
}
