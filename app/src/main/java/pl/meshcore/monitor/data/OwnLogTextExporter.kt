package pl.meshcore.monitor.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OwnLogTextExporter {
    fun save(context: Context, packets: List<LivePacket>): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "M2_MY_LOG_$stamp.txt"
        ExportLocationStore.write(context, fileName, "text/plain", format(packets))
        return fileName
    }

    internal fun format(packets: List<LivePacket>): String = buildString {
        appendLine("M² MY DEVICES LOG")
        appendLine("Exported entries: ${packets.size}")
        appendLine("Order: newest first")
        packets.forEachIndexed { index, packet ->
            appendLine()
            appendLine("================================================================================")
            appendLine("ENTRY ${index + 1}")
            appendLine("Timestamp: ${packet.timestamp}")
            appendLine("Displayed time: ${packet.time}")
            appendLine("Type: ${packet.typeLabel}")
            appendLine("Payload type: ${packet.payloadType}")
            appendLine("Packet ID: ${packet.id}")
            appendLine("Packet hash: ${packet.hash}")
            appendLine("Node name: ${packet.nodeName.orEmpty()}")
            appendLine("Node role: ${packet.nodeRole.orEmpty()}")
            appendLine("Public key: ${packet.publicKey.orEmpty()}")
            appendLine("Observer: ${packet.observerName}")
            appendLine("Observer public key: ${packet.observerPublicKey}")
            appendLine("Own traffic: ${packet.ownTraffic}")
            appendLine("Possible own traffic: ${packet.possibleOwnTraffic}")
            appendLine("Tracked key matches: ${packet.trackedRelations.labels().joinToString(" | ")}")
            appendLine("Possible tracked keys: ${packet.trackedRelations.possibleKeys.joinToString { it.take(4).uppercase() }}")
            appendLine("Route: ${packet.path.joinToString(" -> ")}")
            appendLine("Route type: ${packet.routeType?.toString().orEmpty()}")
            appendLine("RSSI: ${packet.rssi?.toString().orEmpty()}")
            appendLine("SNR: ${packet.snr?.toString().orEmpty()}")
            appendLine("Observation count: ${packet.observationCount}")
            appendLine("First seen: ${packet.firstSeen}")
            appendLine("Detail:")
            appendLine(packet.detail)
            appendLine("Decoded JSON:")
            appendLine(packet.decodedJson)
            appendLine("Raw hex:")
            appendLine(packet.rawHex)
        }
    }
}
