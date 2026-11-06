package kr.stepdrive.app.safety

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.stepdrive.core.safety.PerceptionObservation

/** An OEM or calibrated vision adapter must supply these observations. No steering-based substitute. */
interface PerceptionSource {
    val observations: StateFlow<PerceptionObservation?>
    val availability: String
}

class UnavailablePerceptionSource : PerceptionSource {
    override val observations = MutableStateFlow<PerceptionObservation?>(null)
    override val availability = "차로·장애물 센서 미연결 · 실제 차로/충돌/주차 위치 판단 불가"
}
