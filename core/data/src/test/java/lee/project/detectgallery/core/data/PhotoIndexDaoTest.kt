package lee.project.detectgallery.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoIndexDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoIndexDao
    private lateinit var persons: PersonDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.photoIndexDao()
        persons = db.personDao()
    }

    @After fun tearDown() = db.close()

    private fun photo(id: Long, version: Int = V) =
        PhotoEntity(id, dateTaken = id * 1000, generation = id, dHash = id, indexVersion = version, vectorVersion = version)
    private fun person(name: String? = null, center: FloatArray = floatArrayOf(1f, 0f)) =
        PersonEntity(name = name, center = center, centerVersion = V)
    private companion object { const val V = 1 }
    private fun face(mediaId: Long, personId: Long? = null) =
        FaceEntity(mediaId = mediaId, left = 0f, top = 0f, right = .5f, bottom = .5f, vector = floatArrayOf(1f, 0f), personId = personId)

    // B3: FloatArray 를 쿼리 인자로 넘기면 Room 이 "?,?,…" 로 펼쳐 SQL 오류가 났다. 중심 갱신이 실제로 저장되는지 본다
    @Test fun 인물_중심을_갱신한다() = runTest {
        val id = persons.insert(person("엄마"))
        persons.updateCenters(mapOf(id to floatArrayOf(0f, 1f)), version = 2)

        val row = persons.centers(2).single()
        assertEquals(id, row.personId)
        assertArrayEquals(floatArrayOf(0f, 1f), row.vector, 0f)
    }

    @Test fun 내용이_바뀐_사진의_재분석이_실패하면_옛_장면과_얼굴을_지운다() = runTest {
        dao.replace(listOf(photo(1)), listOf(SceneEmbeddingEntity(1, floatArrayOf(1f))), listOf(face(1), face(1)), emptyList())
        dao.replace(listOf(photo(1, version = 0)), emptyList(), emptyList(), emptyList())

        assertEquals(0, dao.scenes(V).size)
        assertEquals(0, dao.faces(V).size)
        assertEquals(0, dao.states().single().indexVersion)
    }

    @Test fun 같은_사진의_일시_실패는_기존_결과를_두고_버전만_낮춘다() = runTest {
        dao.replace(listOf(photo(1)), listOf(SceneEmbeddingEntity(1, floatArrayOf(1f))), listOf(face(1), face(1)), emptyList())
        dao.markFailed(listOf(1L))

        assertEquals(1, dao.scenes(V).size)
        assertEquals(2, dao.faces(V).size)                       // 인물 연결이 남는다
        assertEquals(0, dao.states().single().indexVersion)     // 다음 실행의 재시도 대상
    }

    @Test fun 두_사람이_함께_나온_사진만_찾는다() = runTest {
        val mom = persons.insert(person("엄마"))
        val dad = persons.insert(person("아빠", floatArrayOf(0f, 1f)))
        dao.replace(listOf(photo(1), photo(2)), emptyList(), listOf(face(1, mom), face(1, dad), face(2, mom)), emptyList())

        assertEquals(listOf(1L), dao.byPersons(listOf("엄마", "아빠"), 2))
        assertEquals(setOf(1L, 2L), dao.byPersons(listOf("엄마"), 1).toSet())
    }

    @Test fun 얼굴도_이름도_없는_인물만_정리한다() = runTest {
        persons.insert(person())                 // 고아
        persons.insert(person("엄마"))  // 이름 있음 → 유지
        assertEquals(1, persons.deleteOrphans())
    }

    @Test fun 나누면_고른_얼굴만_새_인물로_옮겨진다() = runTest {
        val mom = persons.insert(person("엄마"))
        dao.replace(listOf(photo(1), photo(2)), emptyList(), listOf(face(1, mom), face(2, mom)), emptyList())
        val faceIds = dao.facesOf(listOf(1L, 2L)).sortedBy { it.mediaId }.map { it.id }

        val newId = persons.split(listOf(faceIds[1]), floatArrayOf(1f, 0f), V)

        assertEquals(setOf(1L), dao.byPersons(listOf("엄마"), 1).toSet())       // 엄마 쪽엔 사진 1만
        assertEquals(newId, dao.facesOf(listOf(2L)).single().personId)
    }

    @Test fun 얼굴_아님으로_뺀_얼굴은_다시_배정되지도_검색되지도_않는다() = runTest {
        dao.replace(listOf(photo(1)), emptyList(), listOf(face(1), face(1)), emptyList())
        val ids = dao.facesOf(listOf(1L)).map { it.id }

        persons.exclude(listOf(ids[0]))

        assertEquals(listOf(ids[1]), persons.unassignedFaces(V).map { it.id })
        assertEquals(1, dao.faces(V).size)
    }

    @Test fun 인물_기능_끄기는_얼굴과_이름을_모두_지운다() = runTest {
        persons.insert(person("엄마"))
        dao.replace(listOf(photo(1)), emptyList(), listOf(face(1)), emptyList())

        dao.deleteAllFaceData(StateTag.FACE_TAGS.toList())

        assertEquals(0, dao.faces(V).size)
        assertEquals(emptyList<String>(), dao.personNames())
    }

    @Test fun 옛_버전_벡터는_검색과_배정에서_빠진다() = runTest {
        // 차원이 같은 모델로 바꾼 직후: 사진 1 은 아직 옛 버전, 사진 2 는 재색인됨
        dao.replace(listOf(photo(1, version = V - 1), photo(2)), emptyList(), listOf(face(1), face(2)), emptyList())
        persons.insert(person("엄마").copy(centerVersion = V - 1))

        assertEquals(listOf(2L), dao.faces(V).map { it.mediaId })
        assertEquals(dao.facesOf(listOf(2L)).map { it.id }, persons.unassignedFaces(V).map { it.id })
        assertEquals(0, persons.centers(V).size)              // 옛 중심은 배정 후보가 아니다
    }

    @Test fun 얼굴을_전부_뺀_인물은_이름이_있어도_지운다() = runTest {
        val mom = persons.insert(person("엄마"))
        dao.replace(listOf(photo(1)), emptyList(), listOf(face(1, mom)), emptyList())
        val ids = dao.facesOf(listOf(1L)).map { it.id }

        val owners = persons.personIdsOf(ids)      // 빼기 전에 읽는다 (빼면 personId 가 지워진다)
        persons.exclude(ids)
        persons.deleteIfEmpty(owners)

        assertEquals(emptyList<String>(), dao.personNames())
    }

    @Test fun 얼굴을_전부_나눈_이름_인물은_카드로_남고_배정_후보에서_빠진다() = runTest {
        val mom = persons.insert(person("엄마"))
        dao.replace(listOf(photo(1)), emptyList(), listOf(face(1, mom)), emptyList())
        val ids = dao.facesOf(listOf(1L)).map { it.id }

        val owners = persons.personIdsOf(ids)
        val aunt = persons.split(ids, floatArrayOf(1f, 0f), V)
        persons.deleteUnnamedIfEmpty(owners); persons.retireIfEmpty(owners)

        assertEquals(listOf("엄마"), dao.personNames())                      // 이름 카드는 남는다
        assertEquals(listOf(aunt), persons.centers(V).map { it.personId })   // 후보는 새 인물뿐
    }

    // B3: FloatArray 를 쿼리 인자로 넘기면 Room 이 "?,?,…" 로 펼쳐 SQL 오류가 났다. 중심 갱신이 실제로 저장되는지 본다
    @Test
    fun 인물_중심을_갱신한다() = runTest {
        val id = persons.insert(person("엄마"))
        persons.updateCenters(mapOf(id to floatArrayOf(0f, 1f)), version = 2)

        val row = persons.centers(2).single()
        assertEquals(id, row.personId)
        assertArrayEquals(floatArrayOf(0f, 1f), row.vector, 0f)
    }
}
// 같은 자리 얼굴의 인물·"얼굴 아님" 유지는 RoomPhotoIndexRepository 를 통해 시험한다 (save 두 번 → 값 유지 확인).