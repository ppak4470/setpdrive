package kr.stepdrive.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.stepdrive.app.ui.theme.Line
import kr.stepdrive.app.ui.theme.Mu
import kr.stepdrive.app.ui.theme.Surface1
import kr.stepdrive.app.ui.theme.Surface2
import kr.stepdrive.app.ui.theme.Tx

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = Line, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(Surface1, RoundedCornerShape(22.dp))
            .border(1.dp, border, RoundedCornerShape(22.dp))
            .padding(24.dp),
        content = content,
    )
}

@Composable
fun Label(text: String) = Text(text, color = Mu, fontSize = 15.sp, fontWeight = FontWeight.Medium)

@Composable
fun Title(text: String, size: Int = 34) = Text(text, color = Tx, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)

@Composable
fun Chip(text: String, color: Color = Tx, bg: Color = Surface2) {
    Text(
        text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun Metric(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, color: Color = Tx) {
    Column(modifier.background(Surface2, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Label(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            if (unit.isNotEmpty()) { Spacer(Modifier.width(4.dp)); Text(unit, color = Mu, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

@Composable
fun TopBar(title: String, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = Mu, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        right()
    }
}

fun Float?.kmh(): String = this?.let { "${it.toInt()}" } ?: "–"
fun Int?.remain(): String = this?.let { if (it >= 1000) "%.1fkm".format(it / 1000.0) else "${it}m" } ?: "–"
fun Int?.minutes(): String = this?.let { "${(it + 59) / 60}분" } ?: "–"
