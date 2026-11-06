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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.stepdrive.app.ui.UiState
import kr.stepdrive.app.ui.theme.Accent
import kr.stepdrive.app.ui.theme.Line
import kr.stepdrive.app.ui.theme.Mu
import kr.stepdrive.app.ui.theme.Ok
import kr.stepdrive.app.ui.theme.Tx
import kr.stepdrive.app.ui.theme.Warn
import kr.stepdrive.core.model.TripEvent
import kr.stepdrive.core.model.TripScore

@Composable
fun ReportScreen(ui: UiState, trip: TripScore?, onHome: () -> Unit) {
    val p = ui.active
    Column(Modifier.fillMaxSize()) {
        TopBar("오늘의 연습 · P")
        ui.message?.let { Text(it, color = Warn, modifier = Modifier.padding(horizontal = 28.dp)) }
        if (trip == null) { Text("리포트를 준비하고 있어요…", color = Mu, modifier = Modifier.padding(28.dp)); return }
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.4f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Label("${trip.tier.label} 코스 · ${if (trip.distanceEstimated) "추정 " else ""}%.1fkm · ${((trip.endedAtMs - trip.startedAtMs) / 60000).coerceAtLeast(1)}분".format(trip.distanceKm))
                    Title(if (trip.provisional) "평가 보류 · 누적 점수 반영 없음" else "오늘 주행 ${trip.rawScore}점 · +${trip.xp}")
                    if (trip.eventsTruncated) Text("장시간 기록은 최근 2,000건만 표시하며 평가 비율에는 전체 관측을 반영해요.", color = Mu, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Panel(Modifier.weight(1f), border = Ok) { Chip("잘한 점", Ok); Spacer(Modifier.height(12.dp)); Text(trip.praise, color = Tx, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    Panel(Modifier.weight(1f), border = Warn) {
                        Chip("다음 연습 포인트", Warn); Spacer(Modifier.height(12.dp))
                        val np = trip.nextPractice ?: p?.openPractice
                        if (np == null) Text(if (trip.provisional) "평가할 정보가 부족해 이번 점수는 보류했어요" else "다음 코스로 연습해 보세요", color = Tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        else { Text(np.title, color = Tx, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(np.criterion, color = Mu, fontSize = 15.sp) }
                        if (trip.reviewedPractice?.verified == true) Text("이전 연습 목표를 달성했어요", color = Ok, fontSize = 15.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Metric("깜빡이 미리 켜기", trip.signalRate?.let { "${(it * 100).toInt()}" } ?: "–", "%", Modifier.weight(1f))
                    Metric("급제동", "${trip.events.count { it.kind == TripEvent.Kind.HARSH_BRAKE }}${if (trip.eventsTruncated) "+" else ""}", "회", Modifier.weight(1f))
                    Metric("위험 구간", trip.hazardSafeRatio?.let { "${(it * 100).toInt()}" } ?: "–", "% 안전", Modifier.weight(1f))
                    Metric("주차 점검", if (trip.events.any { it.kind == TripEvent.Kind.PARKING_DONE }) "완료" else "–", "", Modifier.weight(1f), color = Ok)
                }
            }
            Panel(Modifier.weight(1f).fillMaxSize()) {
                val skill = p?.skill
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (skill != null) {
                        val tier = skill.unlockedTier()
                        Text(if (skill.graduated()) "🎓" else "✓", fontSize = 64.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(if (skill.graduated()) "졸업 기준을 채웠어요" else "열린 코스: ${tier.label}", color = Tx, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val toNext = skill.xpToNextTier()
                        Text(if (toNext == null) "모든 코스가 열려 있어요" else if (toNext == 0) "${tier.next()?.label} 코스가 열렸어요!" else "${tier.next()?.label}까지 ${toNext}점 남았어요", color = Mu, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("누적 ${skill.xp}점 · 최근 평균 ${skill.recentAverage}점", color = Mu, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onHome, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("홈으로", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(10.dp))
                    Text("가족과는 이 화면을 함께 보세요. 기록은 차 밖으로 나가지 않아요.", color = Mu, fontSize = 14.sp)
                }
            }
        }
    }
}
