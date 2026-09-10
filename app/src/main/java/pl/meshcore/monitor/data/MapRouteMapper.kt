package pl.meshcore.monitor.data

import kotlin.math.*

object MapRouteMapper {
    data class Metrics(val totalUniqueKm: Double = 0.0, val longestRouteKm: Double = 0.0,
        val longestRoute: List<MapNodePoint> = emptyList())

    fun metrics(events: List<MapRouteEvent>, nodes: List<LocatedNode>): Metrics {
        val unique = edges(events, nodes)
        val total = unique.sumOf { distanceKm(it.from.lat, it.from.lon, it.to.lat, it.to.lon) }
        val routes = events.filter { event ->
            !event.uncertainAttribution && event.path.all { it.length >= 4 }
        }.map { withoutLoops(resolve(it.path, nodes)) }
            .filter { route -> route.size > 1 && route.none { it.uncertain } }
        val longestRoute = routes.maxByOrNull { route -> route.zipWithNext().sumOf { (a, b) ->
            distanceKm(a.lat, a.lon, b.lat, b.lon)
        } }.orEmpty()
        val longest = longestRoute.zipWithNext().sumOf { (a, b) -> distanceKm(a.lat, a.lon, b.lat, b.lon) }
        return Metrics(total, longest, longestRoute)
    }
    fun edges(events: List<MapRouteEvent>, nodes: List<LocatedNode>): List<MapEdge> {
        val aggregated = linkedMapOf<String, MapEdge>()
        events.forEach { event ->
            val points = resolve(event.path, nodes)
            points.zipWithNext().forEach { (from, to) ->
                val uncertain = from.uncertain || to.uncertain || event.path.firstOrNull()?.length == 2 || event.uncertainAttribution
                val a = "${from.hash}:${from.lat}:${from.lon}"
                val b = "${to.hash}:${to.lat}:${to.lon}"
                val id = if (a <= b) "$a|$b" else "$b|$a"
                val existing = aggregated[id]
                aggregated[id] = if (existing == null) MapEdge(from, to, uncertain)
                    else existing.copy(count = existing.count + 1, uncertain = existing.uncertain && uncertain)
            }
        }
        return aggregated.values.toList()
    }

    fun resolve(path: List<String>, nodes: List<LocatedNode>): List<MapNodePoint> {
        if (path.size < 2) return emptyList()
        val candidates = path.map { raw ->
            val hop = raw.uppercase()
            nodes.filter { it.publicKey.startsWith(hop) }
                .distinctBy { "${it.publicKey.take(4)}:${it.lat}:${it.lon}" }
        }
        if (candidates.any { it.isEmpty() }) return resolveSegments(path, candidates)

        var costs = DoubleArray(candidates.first().size) { 0.0 }
        val parents = mutableListOf<IntArray>()
        for (index in 1 until candidates.size) {
            val previous = candidates[index - 1]; val current = candidates[index]
            val nextCosts = DoubleArray(current.size) { Double.POSITIVE_INFINITY }
            val nextParents = IntArray(current.size)
            current.forEachIndexed { currentIndex, node ->
                previous.forEachIndexed { previousIndex, prior ->
                    val cost = costs[previousIndex] + distanceKm(prior.lat, prior.lon, node.lat, node.lon)
                    if (cost < nextCosts[currentIndex]) { nextCosts[currentIndex] = cost; nextParents[currentIndex] = previousIndex }
                }
            }
            parents += nextParents; costs = nextCosts
        }
        var chosen = costs.indices.minByOrNull { costs[it] } ?: return emptyList()
        val selected = MutableList(path.size) { 0 }
        selected[path.lastIndex] = chosen
        for (index in path.lastIndex downTo 1) { chosen = parents[index - 1][chosen]; selected[index - 1] = chosen }
        return path.indices.map { index -> candidates[index][selected[index]].let {
            MapNodePoint(it.publicKey.take(4), it.lat, it.lon, path[index].length == 2 || candidates[index].size > 1)
        } }
    }

    /** Removes backtracking loops so a displayed route never returns over an earlier blue segment. */
    private fun withoutLoops(route: List<MapNodePoint>): List<MapNodePoint> {
        val result = mutableListOf<MapNodePoint>()
        val usedEdges = mutableSetOf<String>()
        route.forEach { point ->
            val previous = result.indexOfFirst { sameLocation(it, point) }
            if (previous >= 0) {
                while (result.lastIndex > previous) result.removeAt(result.lastIndex)
                usedEdges.clear()
                result.zipWithNext().forEach { (a, b) -> usedEdges += edgeId(a, b) }
            } else {
                val prior = result.lastOrNull()
                if (prior == null || usedEdges.none { it == edgeId(prior, point) }) {
                    if (prior != null) usedEdges += edgeId(prior, point)
                    result += point
                }
            }
        }
        return result
    }

    private fun coordinateId(point: MapNodePoint) = "%.5f:%.5f".format(java.util.Locale.US, point.lat, point.lon)
    private fun edgeId(a: MapNodePoint, b: MapNodePoint) = listOf(coordinateId(a), coordinateId(b)).sorted().joinToString("|")
    private fun sameLocation(a: MapNodePoint, b: MapNodePoint) =
        distanceKm(a.lat, a.lon, b.lat, b.lon) < SAME_RPT_TOLERANCE_KM

    private const val SAME_RPT_TOLERANCE_KM = 0.35

    private fun resolveSegments(path: List<String>, candidates: List<List<LocatedNode>>): List<MapNodePoint> {
        // Keep every located point in packet order. Unknown positions are omitted,
        // while the surrounding known points remain connected on the map.
        val resolved = mutableListOf<MapNodePoint>()
        candidates.forEachIndexed { index, options ->
            if (options.isNotEmpty()) {
                val selected = resolved.lastOrNull()?.let { prior ->
                    options.minByOrNull { distanceKm(prior.lat, prior.lon, it.lat, it.lon) }
                } ?: options.first()
                resolved += MapNodePoint(selected.publicKey.take(4), selected.lat, selected.lon,
                    path[index].length == 2 || options.size > 1)
            }
        }
        return resolved
    }

    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val value = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(value), sqrt(1 - value))
    }
}
