package pl.meshcore.monitor.data

import kotlin.math.*

object MapRouteMapper {
    fun edges(events: List<MapRouteEvent>, nodes: List<LocatedNode>): List<MapEdge> {
        val aggregated = linkedMapOf<String, MapEdge>()
        events.forEach { event ->
            val points = resolve(event.path, nodes)
            points.zipWithNext().forEach { (from, to) ->
                val uncertain = from.uncertain || to.uncertain || event.path.firstOrNull()?.length == 2
                val a = "${from.hash}:${from.lat}:${from.lon}"
                val b = "${to.hash}:${to.lat}:${to.lon}"
                val id = if (a <= b) "$a|$b|$uncertain" else "$b|$a|$uncertain"
                val existing = aggregated[id]
                aggregated[id] = if (existing == null) MapEdge(from, to, uncertain)
                    else existing.copy(count = existing.count + 1)
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

    private fun resolveSegments(path: List<String>, candidates: List<List<LocatedNode>>): List<MapNodePoint> {
        // Missing positions split a route. We keep only consecutive resolvable points;
        // no line is allowed to jump over an unknown hop.
        var best = emptyList<MapNodePoint>(); var current = mutableListOf<MapNodePoint>()
        candidates.forEachIndexed { index, options ->
            if (options.isEmpty()) { if (current.size > best.size) best = current.toList(); current = mutableListOf() }
            else {
                val selected = current.lastOrNull()?.let { prior ->
                    options.minByOrNull { distanceKm(prior.lat, prior.lon, it.lat, it.lon) }
                } ?: options.first()
                current += MapNodePoint(selected.publicKey.take(4), selected.lat, selected.lon,
                    path[index].length == 2 || options.size > 1)
            }
        }
        if (current.size > best.size) best = current
        return best
    }

    private fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val value = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(value), sqrt(1 - value))
    }
}
