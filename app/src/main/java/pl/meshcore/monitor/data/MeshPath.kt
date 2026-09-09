package pl.meshcore.monitor.data

object MeshPath {
    fun normalize(parts: List<String>): List<String> {
        // The API already returns one entry per hop. Its width follows the
        // packet's path-hash mode and can be 1, 2 or 3 bytes. Combining
        // adjacent one-byte entries corrupts the route and can drop its final
        // hop (for example F4, the one-byte form of a key starting with F480).
        return parts.map { it.trim().uppercase() }.filter(String::isNotBlank)
    }

    fun matchingKeys(route: List<String>, publicKeys: Set<String>): Map<String, List<String>> =
        route.distinct().mapNotNull { hop ->
            val matches = publicKeys.filter { key -> key.startsWith(hop, ignoreCase = true) }
            if (matches.isEmpty()) null else hop to matches
        }.toMap()

    fun endingKeys(route: List<String>, publicKeys: Set<String>): List<String> {
        val lastHop = route.lastOrNull() ?: return emptyList()
        return publicKeys.filter { it.startsWith(lastHop, ignoreCase = true) }
    }

    fun hashSizeBytes(routes: List<List<String>>): Int? = routes.asSequence().flatten()
        .map { it.length / 2 }.filter { it > 0 }.distinct().singleOrNull()
}
