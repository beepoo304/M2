package pl.meshcore.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun GuideScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("guide_settings", android.content.Context.MODE_PRIVATE) }
    var language by rememberSaveable { mutableStateOf(prefs.getString("language", "PL").takeIf { it in listOf("PL", "EN", "RU", "SK", "CZ", "DE", "FR") } ?: "PL") }
    var menu by remember { mutableStateOf(false) }
    val words = guideTranslations.getValue(language)
    val scroll = rememberScrollState()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val green = Color(0xFF42D47B)
    val orange = Color(0xFFF0A84B)
    val blue = Color(0xFF2196F3)
    val purple = Color(0xFFAB47BC)
    val red = Color(0xFFFF6565)
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(words[0], fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton({ menu = true }) { Text(if (language == "SK") "SLO" else language) }
                DropdownMenu(menu, { menu = false }) {
                    listOf("PL", "EN", "RU", "SK", "CZ", "DE", "FR").forEach { code ->
                        DropdownMenuItem(text = { Text(if (code == "SK") "SLO · Slovenčina" else code) },
                            onClick = { language = code; prefs.edit().putString("language", code).apply(); menu = false })
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().padding(end = 10.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GuideSection(words[1]) {
                    GuideRow("—", words[2], green)
                    GuideRow("—", words[3], orange)
                    GuideRow("—", words[4], blue)
                    GuideRow("0652", words[5], purple)
                    GuideRow("XXX km", words[6], red)
                }
                GuideSection(words[8]) {
                    GuideRow("SOURCE", words[9], green)
                    GuideRow("LAST RECORDED HOP", words[10], green)
                    GuideRow("IN ROUTE", words[11], green)
                    GuideRow("REPORTED BY", words[12], green)
                    GuideRow("REPLY TO", words[13], orange)
                    GuideRow("NO ROUTE", words[7], red)
                    GuideRow("NO GPS FOR 0652", words[5], purple)
                    GuideRow("REPEATER / COMPANION / ROOM SERVER / SENSOR", "", MaterialTheme.colorScheme.onSurface)
                }
                GuideSection(words[19]) {
                    listOf("Starts at key", "Last recorded hop", "Related to key", "Reported by key", "All for selected key").forEachIndexed { index, label ->
                        GuideRow(label, words[20 + index], green)
                    }
                }
                GuideSection(words[14]) {
                    GuideRow("Packets · Routes", words[15], green)
                    GuideRow("Distance", words[16], green)
                    GuideRow("MAX HOPS", words[17], green)
                    GuideRow("NO GPS RPT", words[18], purple)
                    GuideRow(words[25], words[26], orange)
                    GuideRow("Longest route", words[4], blue)
                }
                GuideSection(words[27]) {
                    (28..36).forEach { index ->
                        Text("${index - 27}. ${words[index]}", fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    Text("10. ${words[37]}", fontSize = 11.sp, lineHeight = 16.sp)
                    Row(Modifier.fillMaxWidth().clickable {
                        uriHandler.openUri("https://www.instagram.com/m2.meshcore.app/")
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(22.dp)) {
                            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx())
                            drawRoundRect(orange, Offset(size.width * .08f, size.height * .08f),
                                Size(size.width * .84f, size.height * .84f),
                                androidx.compose.ui.geometry.CornerRadius(size.width * .23f), style = stroke)
                            drawCircle(orange, size.width * .2f, style = stroke)
                            drawCircle(orange, size.width * .055f, Offset(size.width * .75f, size.height * .25f))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("@m2.meshcore.app", color = blue, fontSize = 12.sp)
                    }
                }
            }
            Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp)) {
                if (scroll.maxValue > 0) {
                    val content = size.height + scroll.maxValue.toFloat()
                    val thumb = (size.height * size.height / content).coerceAtLeast(24.dp.toPx()).coerceAtMost(size.height)
                    val y = (size.height - thumb) * scroll.value.toFloat() / scroll.maxValue
                    drawRoundRect(Color.Gray.copy(alpha = .7f), Offset(0f, y), Size(size.width, thumb))
                }
            }
        }
    }
}

@Composable private fun GuideSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().border(.5.dp, Color.Gray.copy(alpha = .5f), RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable private fun GuideRow(label: String, description: String, color: Color) {
    Column {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (description.isNotEmpty()) Text(description, fontSize = 11.sp, lineHeight = 15.sp)
    }
}
