package kr.stepdrive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kr.stepdrive.app.ui.theme.Tx
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.stepdrive.app.ui.screens.CourseScreen
import kr.stepdrive.app.ui.screens.DriveScreen
import kr.stepdrive.app.ui.screens.HomeScreen
import kr.stepdrive.app.ui.screens.ProfileScreen
import kr.stepdrive.app.ui.screens.ReportScreen
import kr.stepdrive.app.ui.theme.Bg

/** Single-activity, state-driven navigation: the session phase decides the screen while driving. */
@Composable
fun StepDriveUi(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            if (ui.restricted && ui.screen != Screen.DRIVE) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("안전하게 정차한 뒤 이용해 주세요\n주행 중에는 음성으로 안내해요", color = Tx, fontSize = 28.sp, textAlign = TextAlign.Center)
                }
            } else when (ui.screen) {
                Screen.PROFILE -> ProfileScreen(ui, vm::unlock, vm::createProfile, vm::deleteProfile, vm::clearMessage)
                Screen.HOME -> HomeScreen(ui, vm::buildCourses, vm::lock, vm::demoPlay, vm::demoRestart, vm.lastSummary(ui.active), vm::setSimNav, vm::showDataSettings)
                Screen.COURSE -> CourseScreen(ui, vm::startCourse, vm::goHome, vm::searchDestination)
                Screen.DRIVE -> DriveScreen(session, ui.restricted, vm::listen, vm::goHome)
                Screen.REPORT -> ReportScreen(ui, session.tripScore, vm::goHome)
            }
            if (ui.dataSettings && !ui.restricted) kr.stepdrive.app.ui.screens.DataSettingsDialog(ui, vm::refreshPublicData, vm::closeDataSettings)
        }
    }
}
