package pl.meshcore.monitor.data

object MeshPath {
    fun isReliableHop(value: String): Boolean = value.length in 4..64 && value.length % 2 == 0 && value.all { it.digitToIntOrNull(16) != null }
    fun isTrackable(path: List<String>): Boolean = path.all(::isReliableHop)
    fun normalizeTrace(parts: List<String>): List<String> {
        val normalized = normalize(parts)
        return normalized
    }

    fun normalize(parts: List<String>): List<String> {
        // The API already returns one entry per hop. Its width follows the
        // packet's path-hash mode and can be 1, 2 or 3 bytes. Combining
        // adjacent one-byte entries corrupts the route and can drop its final
        // hop (for example F4, the one-byte form of a key starting with F480).
        return parts.map { it.trim().uppercase() }
    }

    fun matchingKeys(route: List<String>, publicKeys: Set<String>): Map<String, List<String>> =
        route.filter(::isReliableHop).distinct().mapNotNull { hop ->
            val matches = publicKeys.filter { key -> key.startsWith(hop, ignoreCase = true) }
            if (matches.isEmpty()) null else hop to matches
        }.toMap()

    fun endingKeys(route: List<String>, publicKeys: Set<String>): List<String> {
        val lastHop = route.lastOrNull()?.takeIf(::isReliableHop) ?: return emptyList()
        return publicKeys.filter { it.startsWith(lastHop, ignoreCase = true) }
    }

    fun hasReliableEnding(route: List<String>, publicKeys: Set<String>): Boolean =
        route.lastOrNull()?.length?.let { it >= 4 } == true && endingKeys(route, publicKeys).isNotEmpty()

    fun hasExactObserver(route: ObservedRoute, publicKeys: Set<String>): Boolean =
        route.observerPublicKey.isNotBlank() && publicKeys.any {
            it.equals(route.observerPublicKey, ignoreCase = true)
        }

    fun hashSizeBytes(routes: List<List<String>>): Int? = routes.asSequence().flatten()
        .map { it.length / 2 }.filter { it > 0 }.distinct().singleOrNull()
}
