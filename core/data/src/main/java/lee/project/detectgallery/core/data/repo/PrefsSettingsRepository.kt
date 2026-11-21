package lee.project.detectgallery.core.data.repo

import android.content.Context
import lee.project.detectgallery.core.domain.model.Sensitivity
import lee.project.detectgallery.core.domain.port.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import lee.project.detectgallery.core.data.db.AppDatabase

/**
 * DB 에 얼굴이 하나라도 있는가. 설정 값이 없을 때 한 번만 부른다(설치 뒤 첫 실행 또는 이 판정이 생기기 전에 깔린 기기).
 * Room 을 거치지 않고 읽기 전용으로 연다: 생성자에서 동기로 불러야 해서다(작은 쿼리 하나, 평생 한 번).
 * 새로 설치했거나 앱 데이터를 지웠으면 DB 가 없거나 얼굴이 없어 false.
 */
private fun hasFaceData(context: Context): Boolean = runCatching {
    val file = context.getDatabasePath(AppDatabase.NAME)
    if (!file.exists()) return@runCatching false
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        DatabaseUtils.longForQuery(db, "SELECT EXISTS(SELECT 1 FROM face)", null) == 1L
    }
}.getOrDefault(false)   // 표가 아직 없는 등 읽기 실패 = 데이터 없음


/** 값 몇 개라 SharedPreferences 로 충분하다. 메모리 값을 단일 출처로 두고 쓸 때만 디스크에 반영한다. */
@Singleton
class PrefsSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        Sensitivity(prefs.getFloat(KEY_PERSON, 0f), prefs.getFloat(KEY_SCENE, 0f)).clamped(),
    )

    override val sensitivity: Flow<Sensitivity> = state.asStateFlow()
    override fun currentSensitivity() = state.value

    override suspend fun setSensitivity(value: Sensitivity) {
        val v = value.clamped()
        state.value = v
        prefs.edit().putFloat(KEY_PERSON, v.person).putFloat(KEY_SCENE, v.scene).apply()
    }

    // 처음에는 꺼 둔다: 얼굴 특징값은 생체정보에 가까워, 사용자가 인물 탭에서 설명을 보고 켤 때만 만든다.
    // 단 저장된 값이 없는데 얼굴 데이터가 이미 있으면(옛 기본값 "켜짐" 으로 써 온 기기) 켜짐으로 둔다:
    // 꺼짐으로 읽으면 다음 색인 끝 정리가 얼굴과 이름 붙은 인물을 모두 지운다. 정한 값은 저장해 다시 판정하지 않는다.
    private val faces = MutableStateFlow(
        if (prefs.contains(KEY_FACES)) prefs.getBoolean(KEY_FACES, false)
        else hasFaceData(context).also { prefs.edit().putBoolean(KEY_FACES, it).commit() },
    )
    override val facesEnabled: Flow<Boolean> = faces.asStateFlow()
    override fun facesEnabledNow() = faces.value

    /** 끄기는 색인 Worker 가 바로 봐야 하므로 commit (동기 저장) 한다 */
    override suspend fun setFacesEnabled(enabled: Boolean) {
        faces.value = enabled
        prefs.edit().putBoolean(KEY_FACES, enabled).commit()
    }

    private val albumIds = MutableStateFlow(
        prefs.getStringSet(KEY_ALBUMS, null).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
    )
    override val albums: Flow<Set<Long>> = albumIds.asStateFlow()
    override fun albumsNow() = albumIds.value

    /** 프로세스가 종료돼도 범위가 남게 commit (동기 저장) 한다. 같은 프로세스의 색인은 메모리 값(albumIds)을 바로 본다 */
    override suspend fun setAlbums(ids: Set<Long>) {
        albumIds.value = ids
        prefs.edit().putStringSet(KEY_ALBUMS, ids.mapTo(HashSet()) { it.toString() }).commit()
    }

    private companion object {
        const val KEY_PERSON = "sensitivity.person"
        const val KEY_SCENE = "sensitivity.scene"
        const val KEY_FACES = "faces.enabled"
        const val KEY_ALBUMS = "albums"
    }
}