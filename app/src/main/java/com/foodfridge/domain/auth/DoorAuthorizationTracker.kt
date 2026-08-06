package com.foodfridge.domain.auth

data class DoorOperatorSnapshot(
    val operatorId: Int,
    val operatorName: String,
    val authorizedAt: Long,
    val expiresAt: Long,
)

class DoorAuthorizationTracker {
    private var futureAuthorization: DoorOperatorSnapshot? = null
    private var activeDoorCycle: ActiveDoorCycle? = null

    fun authorize(snapshot: DoorOperatorSnapshot) {
        futureAuthorization = snapshot
    }

    fun onDoorOpened(openedAt: Long): DoorOperatorSnapshot? {
        activeDoorCycle?.let { return it.operator }
        val authorization = futureAuthorization
            ?.takeIf { openedAt in it.authorizedAt..it.expiresAt }
            ?: return null
        activeDoorCycle = ActiveDoorCycle(
            operator = authorization,
            openedAt = openedAt,
        )
        return authorization
    }

    fun invalidateFutureAccess() {
        futureAuthorization = null
    }

    fun completeDoorCycle(openedAt: Long, closedAt: Long): DoorOperatorSnapshot? {
        val activeCycle = activeDoorCycle
        val operator = when {
            activeCycle?.openedAt == openedAt -> activeCycle.operator
            else -> futureAuthorization?.takeIf {
                openedAt in it.authorizedAt..it.expiresAt
            }
        }

        activeDoorCycle = null
        if (futureAuthorization?.expiresAt?.let { closedAt > it } == true) {
            futureAuthorization = null
        }
        return operator
    }

    internal fun hasFutureAuthorization(): Boolean = futureAuthorization != null

    internal fun hasActiveDoorCycle(): Boolean = activeDoorCycle != null

    private data class ActiveDoorCycle(
        val operator: DoorOperatorSnapshot,
        val openedAt: Long,
    )
}
