package kr.stepdrive.app.hazard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.hazard.KoRoadParser
import kr.stepdrive.core.model.Hazard
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun interface HazardTransport { fun fetch(url: String): String }
class KoRoadClient(private val transport: HazardTransport = HazardTransport { address ->
    val c = URL(address).openConnection() as HttpURLConnection
    c.connectTimeout = 8_000; c.readTimeout = 8_000; c.instanceFollowRedirects = false
    try {
        check(c.responseCode == 200) { "공공데이터 서버 응답 실패" }
        c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size() + n <= 3_000_000); out.write(buffer, 0, n) }
            out.toString("UTF-8")
        }
    } finally { c.disconnect() }
}) {
    fun load(key: String, year: Int, municipality: String): List<Hazard> {
        require(key.isNotBlank() && key.length <= 512)
        require(year in 2010..java.time.Year.now().value && municipality.matches(Regex("[0-9]{5}")))
        val raw = if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(key)) URLDecoder.decode(key.replace("+", "%2B"), "UTF-8") else key
        val encoded = URLEncoder.encode(raw.trim(), "UTF-8")
        val rows = mutableListOf<Hazard>()
        var expected: Int? = null
        for (page in 1..100) {
            val response = transport.fetch("https://apis.data.go.kr/B552061/frequentzoneLg/getRestFrequentzoneLg" +
                "?serviceKey=$encoded&searchYearCd=$year&siDo=${municipality.take(2)}&guGun=${municipality.takeLast(3)}&type=json&numOfRows=100&pageNo=$page")
            val parsed = KoRoadParser.parse(response, year)
            if (expected == null) expected = parsed.total
            check(parsed.total == expected) { "갱신 중 데이터 변경" }
            rows += parsed.items
            if (rows.size >= parsed.total) {
                check(rows.size == parsed.total && rows.distinctBy { it.id }.size == rows.size) { "중복/누락 데이터" }
                return rows
            }
            check(parsed.items.isNotEmpty()) { "페이지 데이터 누락" }
        }
        error("데이터 페이지 범위 초과")
    }
}

@Serializable private data class HazardCache(val hazards: List<Hazard>, val year: Int, val municipality: String, val fetchedAtMs: Long)
/** Public statistics are optional; real driving otherwise uses navigation caution points. */
class HazardRepository(private val context: Context, private val client: KoRoadClient = KoRoadClient()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "public-hazards.json")
    @Volatile private var cached: HazardCache? = null
    val status = MutableStateFlow("내비 사고구간 안내 사용 · 공공 통계 미연동")
    suspend fun hazards(allowSample: Boolean = false): List<Hazard> = withContext(Dispatchers.IO) {
        if (cached == null && file.exists()) {
            runCatching { check(file.length() <= 4_000_000); json.decodeFromString<HazardCache>(file.readText()) }
                .onSuccess { cache ->
                    cached = cache
                    val old = System.currentTimeMillis() - cache.fetchedAtMs > 30L * 24 * 60 * 60 * 1_000
                    status.value = "공공 통계 ${cache.year}년 · 지역 ${cache.municipality} · ${cache.hazards.size}곳" + if (old) " · 갱신 필요" else ""
                }.onFailure { status.value = "저장된 공공 통계를 읽지 못했어요 · 내비 안내 사용" }
        }
        cached?.hazards ?: if (allowSample) context.assets.open("hazards_sample.json").bufferedReader().use { json.decodeFromString<List<Hazard>>(it.readText()) } else emptyList()
    }
    suspend fun index(allowSample: Boolean = false) = HazardIndex(hazards(allowSample))
    suspend fun refresh(key: String, year: Int, municipality: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val list = client.load(key, year, municipality)
            val cache = HazardCache(list, year, municipality, System.currentTimeMillis())
            val temp = File(context.filesDir, "public-hazards.tmp")
            val bytes = json.encodeToString(cache).toByteArray(Charsets.UTF_8)
            check(bytes.size <= 4_000_000)
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            cached = cache
            status.value = "공공 통계 ${year}년 · 지역 $municipality · ${list.size}곳"
            Result.success(list.size)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            Result.failure(IllegalStateException("갱신하지 못했어요. 인증키·신청 서비스·연도·네트워크를 확인하세요. 기존 데이터는 유지됩니다"))
        }
    }
}
