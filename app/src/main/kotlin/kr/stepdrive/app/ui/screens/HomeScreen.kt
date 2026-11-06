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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.stepdrive.app.di.RuntimeMode
import kr.stepdrive.app.ui.UiState
import kr.stepdrive.app.ui.theme.Accent
import kr.stepdrive.app.ui.theme.Line
import kr.stepdrive.app.ui.theme.Mu
import kr.stepdrive.app.ui.theme.Ok
import kr.stepdrive.app.ui.theme.Tx
import kr.stepdrive.app.ui.theme.Warn
import kr.stepdrive.core.model.Tier

@Composable
fun HomeScreen(ui: UiState, onBuildCourse: () -> Unit, onLock: () -> Unit, onDemoPlay: () -> Unit, onDemoRestart: () -> Unit, lastSummary: String? = null, onSimNav: (Boolean) -> Unit = {}, onDataSettings: () -> Unit = {}) {
    val p = ui.active ?: return
    val skill = p.skill
    val tier = skill.unlockedTier()
    val toNext = skill.xpToNextTier()
    val last = p.trips.lastOrNull()

    Column(Modifier.fillMaxSize()) {
        TopBar("스텝드라이브 · ${p.name}") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.mode == RuntimeMode.DEMO) { Chip("시연 모드", Warn); Spacer(Modifier.width(10.dp)) }
                TextButton(onClick = onLock) { Text("운전자 바꾸기", color = Mu) }
                TextButton(onClick = onDataSettings) { Text("데이터 연결", color = Mu) }
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(Modifier.weight(1.45f).fillMaxSize()) {
                Label("열린 코스")
                Row(verticalAlignment = Alignment.Bottom) {
                    Title("${tier.label}까지")
                    Spacer(Modifier.width(14.dp))
                    if (toNext != null) Chip("${tier.next()!!.label}까지 ${toNext}점", color = Accent) else Chip(if (skill.graduated()) "졸업!" else "모든 코스 열림", color = Ok)
                    Spacer(Modifier.width(10.dp))
                    Chip(when (skill.feedbackLevel) { 1 -> "설명 최소"; 2 -> "설명 줄임"; else -> "설명 전체" }, color = Mu)
                }
                Spacer(Modifier.height(22.dp))
                Label("누적 점수 · 최근 ${skill.recentScores.size}회 평균 ${skill.recentAverage}점")
                Spacer(Modifier.height(10.dp))
                val target = when (tier) { Tier.CITY -> skill.rules.xpToUnlockNationalRoad; Tier.NATIONAL_ROAD -> skill.rules.xpToUnlockHighway; Tier.HIGHWAY -> skill.xp.coerceAtLeast(1) }
                LinearProgressIndicator(progress = { (skill.xp.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(12.dp), color = Accent, trackColor = Line)
                Spacer(Modifier.height(6.dp))
                Text("${skill.xp} / $target 점 · 주행 ${skill.trips}회", color = Mu, fontSize = 15.sp)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Metric("깜빡이 미리 켜기", skill.signalRate?.let { "${(it * 100).toInt()}" } ?: "–", "%", Modifier.weight(1f))
                    Metric("급제동", last?.let { "%.1f".format(it.harshBrakePer10Km) } ?: "–", "회/10km", Modifier.weight(1f))
                    Metric("최근 주행 점수", if (last?.provisional == true) "보류" else last?.rawScore?.toString() ?: "–", "점", Modifier.weight(1f))
                }
                Spacer(Modifier.height(22.dp))
                Label("최근 연습")
                ui.recoveryNotice?.let { Text(it, color = Warn, fontSize = 15.sp) }
                Text(ui.hazardStatus, color = Mu, fontSize = 13.sp)
                Text(ui.sensorStatus, color = Mu, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (lastSummary != null) { Text(lastSummary, color = Tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)) }
                if (p.trips.isEmpty()) Text("첫 주행 데이터로 시작 난이도를 정해요. 짧은 동네 코스부터 시작해 보세요.", color = Mu, fontSize = 17.sp)
                p.trips.takeLast(3).reversed().forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(if (t.provisional) "평가 보류" else if (t.rawScore >= 70) "목표 달성" else "다시 연습", if (!t.provisional && t.rawScore >= 70) Ok else Warn)
                        Spacer(Modifier.width(12.dp))
                        Text(if (t.provisional) "중단 또는 관측 부족 · 누적 점수 반영 없음" else "${t.tier.label} 코스 · ${t.rawScore}점 · +${t.xp}", color = Tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("어디로 연습할까요?", color = Tx, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("내비 목적지로 연습을 시작하고, 추정 난이도와 시연 지점을 확인해 보세요.", color = Mu, fontSize = 17.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBuildCourse, modifier = Modifier.fillMaxWidth().height(68.dp)) { Text("연습 코스 만들기", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Label("다음 연습 포인트")
                    Spacer(Modifier.height(8.dp))
                    val op = p.openPractice
                    if (op == null) Text("첫 주행을 마치면 가장 부족한 항목 하나를 골라 알려 드려요.", color = Mu, fontSize = 17.sp)
                    else {
                        Text(op.title, color = Tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(op.criterion, color = Mu, fontSize = 15.sp)
                    }
                }
                if (ui.mode == RuntimeMode.PLEOS) Panel(Modifier.fillMaxWidth()) {
                    Label("모의 내비 (에뮬레이터 테스트용)")
                    Spacer(Modifier.height(6.dp))
                    Text("에뮬레이터 내비는 위치가 고정이라 회전 안내가 안 나와요. 켜면 대본 경로가 실제 속도만큼 진행돼요.", color = Mu, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { onSimNav(!ui.simNav) }) { Text(if (ui.simNav) "모의 내비 켜짐 · 끄기" else "모의 내비 켜기") }
                }
                if (ui.mode == RuntimeMode.DEMO) Panel(Modifier.fillMaxWidth()) {
                    Label("시연 주행 (에뮬레이터)")
                    Spacer(Modifier.height(8.dp))
                    Text("코스를 고르고 출발하면 2분짜리 시나리오가 재생돼요: 우회전 → 사고다발 교차로 → 고속도로 합류 → 도착 → 후면 주차", color = Mu, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onDemoRestart) { Text("처음으로") }
                        TextButton(onClick = onDemoPlay) { Text(if (ui.demoPlaying) "재생 중…" else "재생") }
                    }
                }
            }
        }
    }
}
