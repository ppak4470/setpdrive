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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.stepdrive.app.session.Phase
import kr.stepdrive.app.session.SessionState
import kr.stepdrive.app.ui.theme.Accent
import kr.stepdrive.app.ui.theme.Bad
import kr.stepdrive.app.ui.theme.Line
import kr.stepdrive.app.ui.theme.Mu
import kr.stepdrive.app.ui.theme.Ok
import kr.stepdrive.app.ui.theme.Tx
import kr.stepdrive.app.ui.theme.Warn
import kr.stepdrive.core.coach.Priority
import kr.stepdrive.core.coach.hasDirectionMismatch
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.requiredSignal
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.parking.ParkingStep

/**
 * While moving: one maneuver, one coaching line, speed — no buttons that need a look.
 * In reverse: the parking coach steps next to the car's own rear camera.
 */
@Composable
fun DriveScreen(s: SessionState, restricted: Boolean, onListen: () -> Unit, onStop: () -> Unit) {
    if (restricted) { RestrictedDriveBody(s, onListen); return }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (s.phase == Phase.PARKING) "주차 음성 코치 · R" else "연습 중 · ${s.nav.remainDistanceM.remain()} 남음") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(s.sample.gear.name.take(1), bg = if (s.phase == Phase.PARKING) Warn else Line, color = if (s.phase == Phase.PARKING) Tx else Mu)
                if (!restricted) { Spacer(Modifier.width(10.dp)); TextButton(onClick = onStop) { Text("연습 종료", color = Mu) } }
            }
        }
        if (s.phase == Phase.PARKING) ParkingBody(s, onListen) else DrivingBody(s, onListen)
    }
}

@Composable
private fun RestrictedDriveBody(s: SessionState, onListen: () -> Unit) {
    val recentWarning = s.lastCoachAtMs?.let { s.sample.tMs - it in 0..5_000 } == true &&
        s.lastCoachPriority in setOf(Priority.WARNING, Priority.DIRECTION_CHECK)
    val main = when {
        recentWarning -> s.lastCoachText
        s.phase == Phase.PARKING -> s.parkingPrompt?.text ?: s.parkingStep.spoken
        s.routeNotice != null -> s.routeNotice
        (s.hazardAheadM ?: Int.MAX_VALUE) <= 500 -> "${s.hazardAheadM}m 앞 주의 구간 · 주변을 살피세요"
        s.nav.next != null -> "${s.nav.next!!.distanceM.toInt() / 10 * 10}m 앞 ${s.nav.next!!.type.label()}"
        else -> "주변을 살피며 차량 내비 안내를 따라 주세요"
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(if (s.phase == Phase.PARKING) "주차 음성 안내" else "운전 중 · 간단 안내")
            Text("${s.sample.speedKmh.kmh()} km/h", color = Tx, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Panel(Modifier.weight(1f).fillMaxWidth()) {
            Text(main, color = if (recentWarning) Warn else Tx,
                fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
        }
        Button(onClick = onListen, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("말하기 · 다시 / 도움말", fontSize = 24.sp)
        }
    }
}

@Composable
private fun DrivingBody(s: SessionState, onListen: () -> Unit) {
    val m = s.nav.next
    val hazardClose = (s.hazardAheadM ?: Int.MAX_VALUE) <= 500
    Row(Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(Modifier.weight(1.6f).fillMaxSize(), border = if (hazardClose) Warn else Line) {
            if (hazardClose) {
                Chip("주의 지점 안내", Warn)
                Spacer(Modifier.height(14.dp))
                Text("${s.hazardAheadM}m 앞\n사고가 잦은 교차로", color = Tx, fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 62.sp)
                Spacer(Modifier.height(10.dp))
                Text("속도를 줄이고 좌우를 살피세요", color = Warn, fontSize = 26.sp)
            } else if (s.routeNotice != null) {
                Label("경로 상태")
                Text(s.routeNotice, color = Warn, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            } else if (m != null) {
                Label(m.description.ifBlank { "다음 안내" })
                Spacer(Modifier.height(10.dp))
                Text("${m.distanceM.toInt() / 10 * 10}m 앞 ${m.type.label()}", color = Tx, fontSize = 54.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
                val need = m.type.requiredSignal
                if (need != null) {
                    if (s.sample.turnSignal == TurnSignal.UNKNOWN) Chip("방향지시등 신호 확인 중", Mu)
                    else if (s.sample.turnSignal == need) Chip("✓ ${if (need == TurnSignal.LEFT) "왼쪽" else "오른쪽"} 깜빡이 확인됨", Ok)
                    else if (hasDirectionMismatch(s.sample, s.nav)) Chip("안내 방향과 깜빡이가 달라요", Warn)
                    else Text("주변을 확인하고 회전을 준비하세요", color = Mu, fontSize = 24.sp)
                }
            } else {
                Label("자유 주행")
                Spacer(Modifier.height(10.dp))
                Text("코스를 따라 달리면 음성으로 안내해요", color = Tx, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            if (s.lastCoachText.isNotBlank()) {
                val c = when (s.lastCoachPriority) { Priority.WARNING, Priority.DIRECTION_CHECK, Priority.NOTICE -> Warn; Priority.PRAISE -> Ok; Priority.COACH -> Accent; else -> Tx }
                Text("🔊 ${s.lastCoachText}", color = c, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(0.75f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), ) {
                Label("현재 속도")
                Text(s.sample.speedKmh.kmh(), color = Tx, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold)
                Row { Label("km/h"); s.nav.speedLimitKmh?.let { Spacer(Modifier.width(10.dp)); Label("제한 $it") } }
            }
            Panel(Modifier.fillMaxWidth()) {
                Label("남은 거리 · 시간")
                Text("${s.nav.remainDistanceM.remain()} · ${s.nav.remainTimeS.minutes()}", color = Tx, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(kr.stepdrive.core.course.ObservedRouteAnalysis.from(s.nav).text, color = Mu, fontSize = 14.sp)
            }
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Label("다음 위험 구간"); Text(s.hazardAheadM.remain(), color = if (hazardClose) Warn else Tx, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onListen, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("🎙 말하기", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ParkingBody(s: SessionState, onListen: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(Modifier.weight(1.2f).fillMaxSize()) {
            Chip("차량 기본 후방 카메라 화면")
            Spacer(Modifier.weight(1f))
            Text("앱은 카메라를 쓰지 않고 핸들 지시도 하지 않아요. 기어·속도·핸들 각도로 체크리스트를 넘기고 음성으로 확인할 점을 알려요.", color = Mu, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), border = Accent) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Chip("주차 체크리스트 · ${s.parkingStep.index.coerceAtMost(4)} / 4단계", Accent)
                    Label("음성으로 안내 중")
                }
                Spacer(Modifier.height(12.dp))
                Text(s.parkingPrompt?.text ?: s.parkingStep.spoken, color = if (s.parkingPrompt?.isWarning == true) Warn else Tx, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val v = s.sample.speedKmh?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) }
                    if (v == null) Chip("속도 신호 확인 중", Mu)
                    else Chip(if (v > 5f) "후진 ${v.toInt()}km/h · 조금 더 천천히" else if (v > 0.5f) "후진 ${v.toInt()}km/h" else "정지", if (v > 5f) Warn else Ok)
                    s.sample.steeringAngleDeg?.takeIf { it.isFinite() }?.let { a -> Chip("핸들 회전량 ${kotlin.math.abs(a).toInt()}°") }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                ParkingStep.entries.filter { it != ParkingStep.DONE }.forEach { st ->
                    val done = s.parkingStep.ordinal > st.ordinal
                    val cur = s.parkingStep == st
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(if (done) "✓" else "${st.index}", if (done || cur) Tx else Mu, bg = if (done) Ok else if (cur) Accent else Line)
                        Spacer(Modifier.width(12.dp))
                        Text(st.short, color = if (cur) Tx else if (done) Mu else Mu, fontSize = 18.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (s.parkingStep == ParkingStep.DONE) { Spacer(Modifier.height(8.dp)); Text("주차 체크리스트를 마쳤어요.", color = Ok, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onListen, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("🎙 \"다음\" · \"다시\"", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

private fun ManeuverType.label() = when (this) {
    ManeuverType.LEFT -> "좌회전"; ManeuverType.RIGHT -> "우회전"
    ManeuverType.MERGE_LEFT -> "왼쪽 합류"; ManeuverType.MERGE_RIGHT -> "오른쪽 합류"
    ManeuverType.EXIT -> "출구"; ManeuverType.UTURN -> "유턴"; ManeuverType.STRAIGHT -> "직진"
    ManeuverType.EXIT_LEFT -> "왼쪽 출구"; ManeuverType.EXIT_RIGHT -> "오른쪽 출구"
    ManeuverType.ARRIVE -> "도착"; ManeuverType.OTHER -> "안내"
}
