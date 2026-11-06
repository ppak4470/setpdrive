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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.stepdrive.app.ui.UiState
import kr.stepdrive.app.ui.theme.Accent
import kr.stepdrive.app.ui.theme.Bad
import kr.stepdrive.app.ui.theme.Mu
import kr.stepdrive.app.ui.theme.Tx
import kr.stepdrive.core.profile.DriverProfile

/** "누가 운전하세요?" — pick a profile and unlock with a PIN, or create one. No account, nothing leaves the car. */
@Composable
fun ProfileScreen(
    ui: UiState,
    onUnlock: (DriverProfile, String) -> Unit,
    onCreate: (String, String) -> Unit,
    onDelete: (DriverProfile) -> Unit,
    onClearMessage: () -> Unit,
) {
    var selected by remember { mutableStateOf<DriverProfile?>(null) }
    var pin by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(36.dp)) {
        Title("누가 운전하세요?", 40)
        Spacer(Modifier.height(6.dp))
        Text("기록은 이 차 안에만 저장되고, PIN으로만 열 수 있어요. 180일 동안 쓰지 않은 프로필은 자동으로 삭제돼요.", color = Mu, fontSize = 17.sp)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Panel(Modifier.weight(1.3f).fillMaxSize()) {
                Label("프로필 선택")
                Spacer(Modifier.height(12.dp))
                if (ui.profiles.isEmpty()) Text("아직 프로필이 없어요. 오른쪽에서 만들어 주세요.", color = Mu, fontSize = 18.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ui.profiles, key = { it.id }) { p ->
                        val isSel = selected?.id == p.id
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selected = p; pin = ""; onClearMessage() }, modifier = Modifier.weight(1f)) {
                                Text(p.name, color = if (isSel) Accent else Tx, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Chip("${p.skill.unlockedTier().label} · ${p.skill.xp}점")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onDelete(p) }) { Text("삭제", color = Bad) }
                        }
                    }
                }
                selected?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) }, label = { Text("${p.name}의 PIN") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onUnlock(p, pin) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("운전 시작", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Panel(Modifier.weight(1f).fillMaxSize()) {
                Label("새 프로필 만들기")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = newName, onValueChange = { newName = it.take(12) }, label = { Text("이름 또는 별명") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newPin, onValueChange = { newPin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN (숫자 4~6자리)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onCreate(newName, newPin) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("만들고 시작", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                ui.message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Bad, fontSize = 16.sp) }
                Spacer(Modifier.weight(1f))
                Text("가입이나 휴대폰 앱 연동이 없어요. 차를 팔기 전에는 '삭제'로 기록을 지워 주세요.", color = Mu, fontSize = 14.sp)
            }
        }
    }
}
