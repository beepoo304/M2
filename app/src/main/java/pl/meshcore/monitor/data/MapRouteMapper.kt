package pl.meshcore.monitor.data

import kotlin.math.*

/** One geometry for drawing, distances and the flight. Unknown GPS never proves a link. */
object MapRouteMapper {
    data class Metrics(val totalUniqueKm: Double = 0.0, val longestRouteKm: Double = 0.0,
        val longestRoute: List<MapNodePoint> = emptyList())

    fun resolve(path: List<String>, nodes: List<LocatedNode>, resolvedPath: List<String> = emptyList(), directory: List<NodeGpsInfo> = MapNodeRepository.directory.value): List<MapNodePoint> {
        if (!MeshPath.isTrackable(path)) return emptyList()
        if (path.withIndex().any { (index, hash) ->
            resolvedPath.getOrNull(index).isNullOrBlank() && nodes.filter { it.publicKey.startsWith(hash,true) }.map { it.publicKey.uppercase() }.distinct().size > 1
        }) return emptyList()
        val result = mutableListOf<MapNodePoint>()
        val missing = mutableListOf<String>()
        var unknown = false
        path.forEachIndexed { index, hash ->
            val full = resolvedPath.getOrNull(index).orEmpty()
            val candidates = nodes.filter { it.publicKey.startsWith(hash, true) }.distinctBy { it.publicKey.uppercase() }
            val node = if (full.length == 64 && full.startsWith(hash, true))
                candidates.singleOrNull { it.publicKey.equals(full, true) }
            else if (full.isBlank()) candidates.singleOrNull() else null
            if (node == null) {
                if (NodeGpsPolicy.confirmedMissingGps(hash, directory, full)) missing += hash else unknown = true
            }
            else {
                result += MapNodePoint(node.publicKey.take(4), node.lat, node.lon,
                    sourceHash = hash, publicKey = node.publicKey, hopIndex = index,
                    missingBefore = if (result.isEmpty()) emptyList() else missing.toList(),
                    unknownBefore = result.isNotEmpty() && unknown)
                missing.clear(); unknown = false
            }
        }
        return result
    }

    private fun route(event: MapRouteEvent, nodes: List<LocatedNode>, directory: List<NodeGpsInfo>) = resolve(event.path, nodes, event.resolvedPath, directory)
    fun measuredKm(route: List<MapNodePoint>): Double = route.zipWithNext()
        .filter { (_, b) -> b.missingBefore.isEmpty() && !b.unknownBefore }
        .sumOf { (a, b) -> distanceKm(a.lat, a.lon, b.lat, b.lon) }

    fun metrics(events: List<MapRouteEvent>, nodes: List<LocatedNode>, directory: List<NodeGpsInfo> = MapNodeRepository.directory.value): Metrics {
        val total = edges(events, nodes, directory).filterNot { it.missingGps }.distinctBy { edgeId(it.from, it.to) }
            .sumOf { distanceKm(it.from.lat, it.from.lon, it.to.lat, it.to.lon) }
        val routes = events.filter { it.longestRouteEligible && !it.replyToSelected && !it.uncertainAttribution && MeshPath.isTrackable(it.path) }
            .flatMap { splitAtUnknown(route(it, nodes, directory)) }.map(::withoutLoops).filter { it.size > 1 }
        val longest = routes.maxByOrNull(::measuredKm).orEmpty()
        return Metrics(total, measuredKm(longest), longest)
    }

    fun edges(events: List<MapRouteEvent>, nodes: List<LocatedNode>, directory: List<NodeGpsInfo> = MapNodeRepository.directory.value): List<MapEdge> {
        val result = linkedMapOf<String, MapEdge>()
        events.filter { !it.inferredLastHop && MeshPath.isTrackable(it.path) }.forEach { event ->
            route(event, nodes, directory).zipWithNext().forEach segment@{ (a, b) ->
                if (b.unknownBefore) return@segment
                val missing = b.missingBefore.isNotEmpty()
                val id = "${if (missing) "gps" else if (event.replyToSelected) "reply" else "route"}:${edgeId(a,b)}"
                val previous = result[id]
                result[id] = previous?.copy(count = previous.count + 1)
                    ?: MapEdge(a, b, event.uncertainAttribution, reply = event.replyToSelected, missingGps = missing)
            }
        }
        return result.values.toList()
    }

    fun splitAtUnknown(route: List<MapNodePoint>): List<List<MapNodePoint>> {
        val groups = mutableListOf<MutableList<MapNodePoint>>()
        route.forEach { point ->
            if (groups.isEmpty() || point.unknownBefore) groups += mutableListOf<MapNodePoint>()
            groups.last() += point
        }
        return groups
    }

    private fun edgeId(a: MapNodePoint, b: MapNodePoint) = listOf(a.publicKey.uppercase(), b.publicKey.uppercase()).sorted().joinToString("|")
    private fun withoutLoops(route: List<MapNodePoint>): List<MapNodePoint> {
        val result = mutableListOf<MapNodePoint>()
        route.forEach { point ->
            val previous = result.indexOfFirst { it.publicKey.equals(point.publicKey, true) }
            if (previous >= 0) while (result.lastIndex > previous) result.removeAt(result.lastIndex)
            else result += point
        }
        return result
    }

    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val value = (sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)).coerceIn(0.0, 1.0)
        return 6371.0 * 2 * atan2(sqrt(value), sqrt(1 - value))
    }
}
