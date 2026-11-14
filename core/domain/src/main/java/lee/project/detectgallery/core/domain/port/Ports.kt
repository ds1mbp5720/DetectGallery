package lee.project.detectgallery.core.domain.port

import lee.project.detectgallery.core.domain.model.*
import kotlinx.coroutines.flow.Flow

/** 갤러리. 구현: MediaStore (:core:data). */
interface PhotoSource {
    /** 지금 보이는 사진 전부. 조회에 실패하면 예외 (빈 목록으로 삼키면 색인 전체가 지워진다). */
    suspend fun refs(): List<PhotoRef>
    suspend fun thumbnail(mediaId: Long, maxSide: Int = 1024): ImageFrame?
    /** 사진 선택기·촬영으로 받은 content:// URI. 색인과 같은 1024px 여야 단체 사진 얼굴이 쿼리에서도 잡힌다. */
    suspend fun load(uri: String, maxSide: Int = 1024): ImageFrame?
}

/** 색인·사진 쿼리용 정밀 분석. 구현: MediaPipe + ML Kit + TFLite (:core:ml). */
interface VisionEncoder {
    /**
     * 얼굴 임베딩 모델 파일이 있는가. 없으면(FaceNet 을 아직 안 넣은 MVP) 얼굴 단계를 건너뛴다.
     * 모델이 있는데 faces() 가 실패하면 그건 일시 오류다 → 사진을 실패로 저장해 재시도한다.
     */
    val facesAvailable: Boolean
    suspend fun scene(frame: ImageFrame): FloatArray            // L2 정규화
    suspend fun faces(frame: ImageFrame): List<DetectedFace>
    suspend fun labels(frame: ImageFrame): List<Label>
}

/** 실시간 카메라용 경량 검출. 바쁠 때 들어온 프레임은 버린다. */
interface LiveDetector {
    val results: Flow<LiveFrameResult>
    fun submit(frame: ImageFrame, timestampMs: Long)
    fun close()
}

/** 문장 → scene 과 같은 공간의 벡터. 모델이 없으면 null (P3 전). */
interface TextEncoder {
    suspend fun encode(text: String): FloatArray?
}

interface PhotoIndexRepository {
    suspend fun indexStates(): Map<Long, IndexedState>
    /** 재색인되는 사진의 얼굴은 같은 자리(IoU ≥ 0.5)의 이전 얼굴에서 인물을 이어받는다. */
    suspend fun save(batch: List<PhotoAnalysis>, version: Int)
    suspend fun delete(ids: Set<Long>)

    suspend fun sceneVectors(): List<IndexedVector>
    suspend fun faceVectors(): List<IndexedVector>
    suspend fun dHashes(): Map<Long, Long>
    suspend fun photosByPerson(names: List<String>): Set<Long>   // 모든 이름이 함께 나온 사진
    suspend fun photosByLabels(labels: List<String>): List<Long> // confidence 내림차순
    suspend fun photosInRange(range: LongRange): Set<Long>

    /**
     * 인물 기능 끄기: 얼굴·인물(이름 포함)을 전부 지우고 모든 사진의 facesDone 을 false 로. 얼굴 벡터 캐시도 비운다.
     * 여러 번 불러도 결과가 같다 (색인이 끝날 때마다 "꺼져 있으면 다시 지우기" 로 쓴다).
     */
    suspend fun deleteAllFaceData()
    /** "얼굴 아님" 처럼 DB 의 얼굴 상태가 바뀐 뒤 얼굴 벡터 캐시를 비운다. 다음 검색 때 DB 에서 다시 읽는다. */
    suspend fun invalidateFaceCache()
}

/** 색인 실행 요청. 구현: WorkManager (:core:data). 화면에서 "지금 다시 색인" 이 필요할 때 쓴다. */
interface IndexTrigger {
    fun requestIndex()
}

interface PersonRepository {
    suspend fun unassignedFaces(): List<FaceRow>
    suspend fun centers(): List<PersonCenter>
    suspend fun create(center: FloatArray): Long
    /** (faceId, personId) 묶어서 한 번에. 없어진 인물을 가리키는 얼굴은 이때 미배정으로 되돌린다. */
    suspend fun assign(assignments: List<Pair<Long, Long>>)
    suspend fun rename(personId: Long, name: String)
    suspend fun merge(from: Long, into: Long)
    /** 중심이 낡았다고 기록한다 (재색인을 시작할 때). 실행이 중간에 끊겨도 다음 실행이 재계산하게 한다. */
    suspend fun markCentersDirty()
    /**
     * 소속 얼굴 벡터의 평균으로 인물 중심을 다시 계산. force 가 아니면 낡았다고 기록된 경우에만 한다.
     * 합치기·재색인·모델 교체 뒤에 부른다.
     */
    suspend fun recomputeCenters(force: Boolean = false)
    /** 얼굴이 하나도 없고 이름도 없는 인물 삭제. 지운 수를 돌려준다. */
    suspend fun deleteOrphans(): Int
    fun persons(): Flow<List<Person>>

    /** 인물 상세: 이 인물로 묶인 얼굴들 (최신 사진부터). */
    fun faces(personId: Long): Flow<List<PersonFace>>
    /** 고른 얼굴들을 새 인물로 떼어 낸다. 새 인물 id 를 돌려준다. */
    suspend fun split(faceIds: List<Long>): Long?   // 고른 얼굴이 이미 없으면 null
    /**
     * "이 사람 얼굴이 아님"(오검출·남의 얼굴). 인물에서 빼고 다시 배정·검색되지 않게 표시한다.
     * 재색인돼도 같은 자리 얼굴이면 표시를 이어받는다. 실제로 바꾼 얼굴 수를 돌려준다.
     */
    suspend fun excludeFaces(faceIds: List<Long>): Int
}

interface QueryVocabulary {
    suspend fun personNames(): Set<String>
    suspend fun labelsForAlias(alias: String): List<String>
}

interface IndexStatus {
    val progress: Flow<IndexProgress?>   // null = 색인 중 아님
}

/** 사용자 설정. 구현: SharedPreferences (:core:data). */
interface SettingsRepository {
    val sensitivity: Flow<Sensitivity>
    fun currentSensitivity(): Sensitivity
    suspend fun setSensitivity(value: Sensitivity)

    /** 인물 기능(얼굴 분석·인물 검색) 사용 여부. 끄면 얼굴 데이터를 지우고 더 만들지 않는다. */
    val facesEnabled: Flow<Boolean>
    fun facesEnabledNow(): Boolean
    suspend fun setFacesEnabled(enabled: Boolean)
}