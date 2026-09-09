package pl.meshcore.monitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

internal object TrackedNameRanges {
    fun find(text: String, trackedNames: Set<String>): List<IntRange> {
        val occupied = BooleanArray(text.length)
        return trackedNames.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .sortedByDescending(String::length)
            .flatMap { name ->
                sequence {
                    var from = 0
                    while (from < text.length) {
                        val found = text.indexOf(name, from, ignoreCase = true)
                        if (found < 0) break
                        var start = found
                        var end = found + name.length - 1
                        if (start >= 2 && text.substring(start - 2, start) == "@[" && end + 1 < text.length && text[end + 1] == ']') {
                            start -= 2; end += 1
                        } else if (start >= 1 && text[start - 1] == '@') start -= 1
                        if ((start..end).none { occupied[it] }) {
                            (start..end).forEach { occupied[it] = true }
                            yield(start..end)
                        }
                        from = found + name.length
                    }
                }
            }
            .sortedBy { it.first }
            .toList()
    }
}

@Composable
internal fun TrackedNameText(
    text: String,
    trackedNames: Set<String>,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
) {
    val ranges = TrackedNameRanges.find(text, trackedNames)
    val highlight = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        var position = 0
        ranges.forEach { range ->
            append(text.substring(position, range.first))
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) {
                append(text.substring(range.first, range.last + 1))
            }
            position = range.last + 1
        }
        append(text.substring(position))
    }
    Text(annotated, modifier = modifier, color = color, style = style)
}
