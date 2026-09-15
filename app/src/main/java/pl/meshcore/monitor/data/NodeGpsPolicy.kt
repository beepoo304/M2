package pl.meshcore.monitor.data

data class NodeGpsInfo(val publicKey: String, val hasGps: Boolean, val isRepeater: Boolean = true)

object NodeGpsPolicy {
    fun confirmedMissingGps(hash: String, directory: List<NodeGpsInfo>, resolvedKey: String = ""): Boolean {
        if (!MeshPath.isReliableHop(hash)) return false
        val candidates = directory.filter { it.publicKey.startsWith(hash, true) }.distinctBy { it.publicKey.uppercase() }
        val node = if (resolvedKey.isNotBlank()) {
            if (!resolvedKey.startsWith(hash, true)) return false
            candidates.singleOrNull { it.publicKey.equals(resolvedKey, true) }
        } else candidates.singleOrNull()
        return node?.let { it.isRepeater && !it.hasGps } == true
    }
}
