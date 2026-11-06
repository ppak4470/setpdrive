package kr.stepdrive.app.navi

import ai.pleos.playground.navi.constants.TurnType
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.requiredSignal
import kotlin.test.*

class NavigationDirectionTest {
    @Test fun leftExitRequestsLeftIndicator() {
        assertEquals(TurnSignal.LEFT, TurnType.TURN_EXIT_IC_L.toManeuverType().requiredSignal)
    }
    @Test fun rightExitRequestsRightIndicator() {
        assertEquals(TurnSignal.RIGHT, TurnType.TURN_EXIT_IC_R.toManeuverType().requiredSignal)
    }
    @Test fun centralExitDoesNotInventDirection() {
        assertNull(TurnType.TURN_EXIT_IC_C.toManeuverType().requiredSignal)
    }
}
