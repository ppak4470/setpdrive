package kr.stepdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.stepdrive.app.ui.UiState
import kr.stepdrive.app.ui.theme.Accent
import kr.stepdrive.app.ui.theme.Bad
import kr.stepdrive.app.ui.theme.Line
import kr.stepdrive.app.ui.theme.Mu
import kr.stepdrive.app.ui.theme.Ok
import kr.stepdrive.app.ui.theme.Tx
import kr.stepdrive.app.ui.theme.Warn
import kr.stepdrive.core.model.EvaluatedCourse

@Composable
fun CourseScreen(ui: UiState, onStart: (EvaluatedCourse) -> Unit, onBack: () -> Unit, onSearch: (String) -> Unit = {}) {
    val driverTier = ui.active?.skill?.unlockedTier()
    var keyword by remember { mutableStateOf("") }
    val destName = ui.courses.firstOrNull()?.candidate?.destinationName
    Column(Modifier.fillMaxSize()) {
        TopBar("연습 코스 · 열린 난이도: ${driverTier?.label ?: "-"}${if (ui.courseSource.isNotBlank()) " · " + ui.courseSource else ""}") { TextButton(onClick = onBack) { Text("홈으로", color = Mu) } }
        Column(Modifier.padding(horizontal = 28.dp)) {
            Title("어디로 연습할까요?", 30)
            Spacer(Modifier.height(4.dp))
            Text(if (ui.courses.any { !it.candidate.detailsVerified }) "실제 경로와 도착 시간은 차량 내비에서 확인해 주세요." else "모의 코스와 위험 지점은 시연 자료예요.", color = Mu, fontSize = 15.sp)
            if (ui.courses.isEmpty()) Text("내비에서 목적지를 검색하면 여기에 표시돼요.", color = Tx, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = keyword, onValueChange = { keyword = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("가고 싶은 곳을 입력하세요 (예: 세명대학교)", color = Mu) },
                )
                Button(onClick = { onSearch(keyword) }, modifier = Modifier.height(56.dp), enabled = keyword.isNotBlank()) { Text("내비에서 검색", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            ui.message?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Accent, fontSize = 15.sp) }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(ui.courses, key = { it.candidate.id }) { c ->
                val locked = driverTier != null && c.tier > driverTier
                Panel(Modifier.fillMaxWidth(), border = if (c.recommended) Accent else Line) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.candidate.name, color = Tx, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(12.dp))
                                if (c.recommended) Chip("추천", Accent)
                                Spacer(Modifier.width(8.dp))
                                Chip(if (c.candidate.detailsVerified) "${c.tier.label} · 난이도 ${c.difficultyScore}" else "도로 난이도 미확인")
                            }
                            Spacer(Modifier.height(10.dp))
                            if (c.candidate.detailsVerified) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (c.hazardsOnRoute.isEmpty()) Chip("시연 지점 0곳", Ok) else Chip("시연 지점 ${c.hazardsOnRoute.size}곳", Bad)
                                if (c.candidate.features.merges > 0) Chip("합류 ${c.candidate.features.merges}회", Warn)
                                Chip("예상 교차로 ${c.candidate.features.intersections}")
                            }
                            if (c.reason.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(c.reason, color = if (c.recommended) Tx else Mu, fontSize = 17.sp) }
                        }
                        Spacer(Modifier.width(20.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (c.candidate.detailsVerified) "${c.candidate.durationS / 60}분" else "내비에서 확인", color = Tx, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                            if (c.candidate.detailsVerified) Text("%.1fkm".format(c.candidate.distanceM / 1000.0), color = Mu, fontSize = 15.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { onStart(c) }, enabled = !locked, modifier = Modifier.height(56.dp)) {
                                Text(if (locked) "잠김" else "내비로 출발", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
