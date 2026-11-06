package kr.stepdrive.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kr.stepdrive.app.ui.UiState

@Composable
fun DataSettingsDialog(ui: UiState, onRefresh: (String, String, String) -> Unit, onClose: () -> Unit) {
    // Deliberately not rememberSaveable: do not persist the key in instance state or source files.
    var key by remember { mutableStateOf("") }
    var year by remember { mutableStateOf((java.time.Year.now().value - 2).toString()) }
    var region by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("공공 사고 통계 · 선택 연결") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기본 차량·내비 기능은 인증키 없이 동작해요. 지자체별 사고다발지역 통계를 추가할 때만 입력하세요.")
            Text(ui.hazardStatus)
            OutlinedTextField(key, { key = it }, label = { Text("공공데이터 인증키") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("통계 연도") }, singleLine = true)
            OutlinedTextField(region, { region = it.filter(Char::isDigit).take(5) }, label = { Text("시도+시군구 코드 5자리 (예: 11680)") }, singleLine = true)
            Text("인증키는 저장하지 않아요. 통계의 대상 연도·지역을 확인하세요.")
            ui.message?.let { Text(it) }
        }
    }, confirmButton = {
        TextButton(enabled = !ui.dataBusy && key.isNotBlank() && year.length == 4 && region.length == 5,
            onClick = { onRefresh(key, year, region); key = "" }) { Text(if (ui.dataBusy) "갱신 중" else "통계 갱신") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("닫기") } })
}
