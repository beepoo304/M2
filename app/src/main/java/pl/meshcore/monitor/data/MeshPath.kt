package pl.meshcore.monitor.data

object MeshPath {
    fun normalize(parts: List<String>): List<String> {
        val clean = parts.map { it.trim().uppercase() }.filter(String::isNotBlank)
        return if (clean.isNotEmpty() && clean.all { it.length == 2 }) {
            clean.chunked(2).map { it.joinToString("") }.filter { it.length == 4 }
        } else clean
    }
}
