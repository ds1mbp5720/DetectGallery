package lee.project.detectgallery.core.domain.logic

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import lee.project.detectgallery.core.domain.model.DetectedFace
import lee.project.detectgallery.core.domain.model.FaceRow
import lee.project.detectgallery.core.domain.model.ImageFrame
import lee.project.detectgallery.core.domain.model.IndexedState
import lee.project.detectgallery.core.domain.model.IndexedVector
import lee.project.detectgallery.core.domain.model.Label
import lee.project.detectgallery.core.domain.model.Person
import lee.project.detectgallery.core.domain.model.PersonCenter
import lee.project.detectgallery.core.domain.model.PersonFace
import lee.project.detectgallery.core.domain.model.PhotoAnalysis
import lee.project.detectgallery.core.domain.model.PhotoRef
import lee.project.detectgallery.core.domain.model.Sensitivity
import lee.project.detectgallery.core.domain.port.PersonRepository
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.domain.port.SettingsRepository
import lee.project.detectgallery.core.domain.port.VisionEncoder
import lee.project.detectgallery.core.domain.usecase.AssignPersonsUseCase
import lee.project.detectgallery.core.domain.usecase.IndexNewPhotosUseCase
import org.junit.Test

class IndexNewPhotosUseCaseTest {
    private val frame = ImageFrame(4, 4, IntArray(16) { 0xFF808080.toInt() })

    private class FakeSource(var refs: List<PhotoRef>) : PhotoSource {
        val thumbnails = mutableListOf<Long>()
        override suspend fun refs() = refs
        override suspend fun thumbnail(mediaId: Long, maxSide: Int): ImageFrame? {
            thumbnails += mediaId; return ImageFrame(4, 4, IntArray(16))
        }

        override suspend fun load(uri: String, maxSide: Int): ImageFrame? = null
        override suspend fun albums() =
            emptyList<lee.project.detectgallery.core.domain.model.Album>()
    }

    private class FakeVision(
        override val facesAvailable: Boolean = true,
        val faceFails: Boolean = false,
        val sceneVector: FloatArray = floatArrayOf(1f, 0f),
    ) : VisionEncoder {
        override suspend fun scene(frame: ImageFrame) = sceneVector
        override suspend fun faces(frame: ImageFrame): List<DetectedFace> =
            if (faceFails) error("일시 오류") else emptyList()

        override suspend fun labels(frame: ImageFrame) = emptyList<Label>()
    }

    /** 저장된 상태와 저장 호출만 흉내 낸다. 나머지 조회는 쓰지 않는다. */
    private class FakeIndex(val states: MutableMap<Long, IndexedState> = mutableMapOf()) :
        PhotoIndexRepository {
        val saved = mutableListOf<PhotoAnalysis>()
        val deleted = mutableSetOf<Long>()
        override suspend fun indexStates() = states.toMap()
        override suspend fun save(batch: List<PhotoAnalysis>, version: Int) {
            saved += batch
            batch.forEach {
                states[it.ref.mediaId] = IndexedState(
                    it.ref.generation,
                    if (it.failed) 0 else version,
                    !it.failed && it.facesDone
                )
            }
        }

        override suspend fun delete(ids: Set<Long>) {
            deleted += ids; ids.forEach(states::remove)
        }

        override suspend fun sceneVectors() = emptyList<IndexedVector>()
        override suspend fun faceVectors() = emptyList<IndexedVector>()
        override suspend fun dHashes() = emptyMap<Long, Long>()
        override suspend fun photosByPerson(names: List<String>) = emptySet<Long>()
        override suspend fun photosByLabels(labels: List<String>) = emptyList<Long>()
        override suspend fun photosInRange(range: LongRange) = emptySet<Long>()
        override suspend fun deleteAllFaceData() {}
        override suspend fun invalidateFaceCache() {}
    }

    private class FakeSettings(var faces: Boolean = true) : SettingsRepository {
        override val sensitivity = kotlinx.coroutines.flow.flowOf(Sensitivity())
        override fun currentSensitivity() = Sensitivity()
        override suspend fun setSensitivity(value: Sensitivity) {}
        override val facesEnabled = kotlinx.coroutines.flow.flowOf(faces)
        override fun facesEnabledNow() = faces
        override suspend fun setFacesEnabled(enabled: Boolean) {
            faces = enabled
        }

        override val albums = kotlinx.coroutines.flow.flowOf(emptySet<Long>())
        override fun albumsNow() = emptySet<Long>()
        override suspend fun setAlbums(ids: Set<Long>) {}
    }

    private object NoPersons : PersonRepository {
        override suspend fun unassignedFaces() = emptyList<FaceRow>()
        override suspend fun centers() = emptyList<PersonCenter>()
        override suspend fun create(center: FloatArray) = 0L
        override suspend fun assign(assignments: List<Pair<Long, Long>>) {}
        override suspend fun rename(personId: Long, name: String) {}
        override suspend fun merge(from: Long, into: Long) {}
        override suspend fun markCentersDirty() {}
        override suspend fun recomputeCenters(force: Boolean) {}
        override suspend fun deleteOrphans() = 0
        override fun persons() = kotlinx.coroutines.flow.emptyFlow<List<Person>>()
        override fun faces(personId: Long) = kotlinx.coroutines.flow.emptyFlow<List<PersonFace>>()
        override suspend fun split(faceIds: List<Long>): Long? = null
        override suspend fun excludeFaces(faceIds: List<Long>) = 0
    }

    /** 미배정 얼굴 하나, 맞는 인물 없음. 새 인물 생성 횟수를 센다 (B6). */
    private class CountingPersons(val lowQuality: Boolean = false) : PersonRepository by NoPersons {
        var created = 0
        override suspend fun unassignedFaces() =
            listOf(FaceRow(1, floatArrayOf(1f, 0f), lowQuality))

        override suspend fun create(center: FloatArray): Long = (++created).toLong()
    }

    @Test
    fun `재색인 중이면 새 인물을 만들지 않고 미배정으로 둔다`() = runTest {
        val persons = CountingPersons()
        AssignPersonsUseCase(persons).invoke(createNew = false)
        assertEquals(0, persons.created)
        AssignPersonsUseCase(persons).invoke()
        assertEquals(1, persons.created)
    }

    @Test
    fun `옛 버전으로 성공한 사진이 있으면 재색인 중, 실패(0)만 있으면 아니다`() {
        assertTrue(IndexNewPhotosUseCase.isMigrating(mapOf(1L to IndexedState(1, 1)), current = 2))
        assertFalse(IndexNewPhotosUseCase.isMigrating(mapOf(1L to IndexedState(1, 0)), current = 2))
        assertFalse(IndexNewPhotosUseCase.isMigrating(mapOf(1L to IndexedState(1, 2)), current = 2))
    }

    @Test
    fun `저품질 얼굴은 맞는 인물이 없어도 새 인물을 만들지 않는다`() = runTest {
        val persons = CountingPersons(lowQuality = true)
        AssignPersonsUseCase(persons).invoke()
        assertEquals(0, persons.created)
    }

    @Test
    fun `재색인이 없으면 맞는 인물이 없는 얼굴로 새 인물을 만든다`() = runTest {
        val persons = CountingPersons()
        useCase(FakeSource(listOf(PhotoRef(1, 100, 1))), FakeIndex(), persons = persons).invoke()
        assertTrue(persons.created > 0)   // invoke·indexOnce 의 createNew = !migrating 이 뒤집히면 0
    }

    @Test
    fun `장면 벡터에 NaN 이 있으면 실패로 저장해 다음에 재시도한다`() = runTest {
        val index = FakeIndex()
        useCase(
            FakeSource(listOf(PhotoRef(1, 100, 1))),
            index,
            FakeVision(sceneVector = floatArrayOf(Float.NaN, 0f))
        ).invoke()
        assertTrue(index.saved.single().failed)
    }

    private fun useCase(
        source: PhotoSource,
        index: PhotoIndexRepository,
        vision: VisionEncoder = FakeVision(),
        settings: SettingsRepository = FakeSettings(),
        persons: PersonRepository = NoPersons,
    ) = IndexNewPhotosUseCase(
        source,
        vision,
        index,
        persons,
        AssignPersonsUseCase(persons),
        settings
    )

    @Test
    fun `인물 기능을 끄면 얼굴을 분석하지 않는다`() = runTest {
        val index = FakeIndex()
        val vision = FakeVision(faceFails = true)   // 불리면 실패하므로, 실패로 저장되지 않았다면 안 불린 것
        useCase(
            FakeSource(listOf(PhotoRef(1, 100, 1))),
            index,
            vision,
            FakeSettings(faces = false)
        ).invoke()
        assertFalse(index.saved.single().failed)
        assertTrue(index.saved.single().faces.isEmpty())
    }

    @Test
    fun `이미 같은 변경 번호로 색인된 사진은 건너뛰고 나머지만 분석한다`() = runTest {
        val v = IndexNewPhotosUseCase.INDEX_VERSION
        val source =
            FakeSource(listOf(PhotoRef(1, 100, 7), PhotoRef(2, 200, 8), PhotoRef(3, 300, 9)))
        val index =
            FakeIndex(mutableMapOf(1L to IndexedState(7, v), 2L to IndexedState(5, v)))  // 2 는 수정됨
        useCase(source, index).invoke()
        assertEquals(listOf(3L, 2L), source.thumbnails)      // 최신 사진부터, 1 은 건너뜀
    }

    @Test
    fun `권한 변경으로 새로 보이는 사진은 변경 번호가 옛 값이어도 대상이다`() = runTest {
        val source = FakeSource(listOf(PhotoRef(9, 100, 1)))
        val index = FakeIndex()
        useCase(source, index).invoke()
        assertEquals(listOf(9L), source.thumbnails)
    }

    @Test
    fun `갤러리가 비어 보이면 색인을 지우지 않는다`() = runTest {
        val index = FakeIndex(mutableMapOf(1L to IndexedState(1, 1)))
        useCase(FakeSource(emptyList()), index).invoke()
        assertTrue(index.deleted.isEmpty())
    }

    @Test
    fun `얼굴 모델이 없으면 얼굴만 건너뛰고 장면은 저장된다`() = runTest {
        val index = FakeIndex()
        useCase(
            FakeSource(listOf(PhotoRef(1, 100, 1))),
            index,
            FakeVision(facesAvailable = false)
        ).invoke()
        assertFalse(index.saved.single().failed)
        assertNotNull(index.saved.single().scene)
    }

    @Test
    fun `얼굴 모델이 있는데 실패하면 실패로 저장해 다음에 재시도한다`() = runTest {
        val index = FakeIndex()
        useCase(
            FakeSource(listOf(PhotoRef(1, 100, 1))),
            index,
            FakeVision(faceFails = true)
        ).invoke()
        assertTrue(index.saved.single().failed)
        assertEquals(0, index.indexStates().getValue(1L).version)   // 다음 실행의 대상
    }
}