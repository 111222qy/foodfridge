package com.foodfridge.domain.constants

object PublicLibraryConfig {
    const val PUBLIC_LIBRARY_NAME: String = "公共菜品库"

    val LEGACY_PUBLIC_LIBRARY_ALIASES: Set<String> = setOf(
        "A区五食堂"
    )

    fun isPublicLibraryName(rawName: String?): Boolean {
        val name = rawName?.trim().orEmpty()
        if (name.isBlank()) return false
        return name == PUBLIC_LIBRARY_NAME || LEGACY_PUBLIC_LIBRARY_ALIASES.contains(name)
    }

    fun toCanonicalStallName(rawName: String): String {
        val name = rawName.trim()
        if (name.isBlank()) return name
        return if (isPublicLibraryName(name)) PUBLIC_LIBRARY_NAME else name
    }

    fun candidateStoreNames(rawName: String?): List<String> {
        val raw = rawName?.trim().orEmpty()
        val candidates = LinkedHashSet<String>()

        if (raw.isNotBlank()) {
            candidates.add(raw)
        }

        val canonical = if (raw.isBlank()) "" else toCanonicalStallName(raw)
        if (canonical.isNotBlank()) {
            candidates.add(canonical)
        }

        if (isPublicLibraryName(raw) || canonical == PUBLIC_LIBRARY_NAME) {
            candidates.add(PUBLIC_LIBRARY_NAME)
            candidates.addAll(LEGACY_PUBLIC_LIBRARY_ALIASES)
        }

        return candidates.filter { it.isNotBlank() }
    }
}