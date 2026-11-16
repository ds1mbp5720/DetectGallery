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

/** 값 두 개라 SharedPreferences 로 충분하다. 메모리 값을 단일 출처로 두고 쓸 때만 디스크에 반영한다. */
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

    private val faces = MutableStateFlow(prefs.getBoolean(KEY_FACES, true))
    override val facesEnabled: Flow<Boolean> = faces.asStateFlow()
    override fun facesEnabledNow() = faces.value

    /** 끄기는 색인 Worker 가 바로 봐야 하므로 commit (동기 저장) 한다 */
    override suspend fun setFacesEnabled(enabled: Boolean) {
        faces.value = enabled
        prefs.edit().putBoolean(KEY_FACES, enabled).commit()
    }

    private companion object {
        const val KEY_PERSON = "sensitivity.person"
        const val KEY_SCENE = "sensitivity.scene"
        const val KEY_FACES = "faces.enabled"
    }
}