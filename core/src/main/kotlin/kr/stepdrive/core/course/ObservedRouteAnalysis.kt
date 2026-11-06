package kr.stepdrive.core.course

import kr.stepdrive.core.model.*

data class ObservedRouteAnalysis(val turns: Int, val merges: Int, val exits: Int) {
    val text: String get() = "수신 안내: 회전 ${turns}곳 · 합류 ${merges}곳 · 출구 ${exits}곳"
    companion object {
        fun from(nav: NavState): ObservedRouteAnalysis {
            val list = nav.maneuvers
            return ObservedRouteAnalysis(
                list.count { it.type in setOf(ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.UTURN) },
                list.count { it.type in setOf(ManeuverType.MERGE_LEFT, ManeuverType.MERGE_RIGHT) },
                list.count { it.type in setOf(ManeuverType.EXIT, ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT) })
        }
    }
}
