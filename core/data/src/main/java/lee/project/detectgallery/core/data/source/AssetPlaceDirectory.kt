package lee.project.detectgallery.core.data.source

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lee.project.detectgallery.core.domain.logic.distanceKm
import lee.project.detectgallery.core.domain.model.GeoPoint
import lee.project.detectgallery.core.domain.model.Place
import lee.project.detectgallery.core.domain.port.PlaceDirectory
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 시·군·구 중심 좌표 표(assets/places_kr.json)로 가장 가까운 지명을 찾는다 (C12). 네트워크를 쓰지 않는다:
 * Android Geocoder 는 좌표를 Google 서버로 보낸다 — "사진·정보가 기기 밖으로 안 나감" 과 맞지 않는다.
 * 약 250곳이라 전부 비교한다. 처음 부를 때 한 번 읽는다.
 */
@Singleton
class AssetPlaceDirectory @Inject constructor(@ApplicationContext private val context: Context) :
    PlaceDirectory {
    private val lock = Mutex()
    private var table: List<Pair<GeoPoint, Place>>? = null

    override suspend fun nearest(p: GeoPoint): Place? {
        val t = lock.withLock { table ?: load().also { table = it } }
        val best = t.minByOrNull { distanceKm(it.first, p) } ?: return null
        return best.second.takeIf { distanceKm(best.first, p) <= MAX_KM }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        val arr =
            JSONArray(context.assets.open("places_kr.json").bufferedReader().use { it.readText() })
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            GeoPoint(o.getDouble("lat"), o.getDouble("lon")) to Place(
                o.getString("sido"),
                o.getString("sigungu")
            )
        }
    }

    private companion object {
        const val MAX_KM = 40.0   // 가장 가까운 시·군·구 중심이 이보다 멀면 바다 위·해외로 보고 지명을 붙이지 않는다
    }
}
// ponytail: 경계가 아니라 중심점까지 거리로 고른다. 큰 시·군 경계 근처에는 옆 지명이 붙을 수 있다 — 틀리면 경계 다각형으로.