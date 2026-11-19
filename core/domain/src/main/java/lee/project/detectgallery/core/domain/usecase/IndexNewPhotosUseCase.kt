package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.logic.StateTag
import lee.project.detectgallery.core.domain.logic.dHash
import lee.project.detectgallery.core.domain.logic.stateTags
import lee.project.detectgallery.core.domain.model.PhotoAnalysis
import lee.project.detectgallery.core.domain.model.PhotoRef
import lee.project.detectgallery.core.domain.port.PersonRepository
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.domain.port.SettingsRepository
import lee.project.detectgallery.core.domain.port.VisionEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import javax.inject.Inject

/**
 * 사진마다 "MediaStore 변경 번호 + 색인 버전"을 저장해 두고, 다른 사진만 다시 분석한다.
 * - 중간에 멈춰도(WorkManager 작업 10분 제한, 프로세스 종료) 끝난 사진은 건너뛰므로 다음 실행이 이어서 한다.
 * - 권한 변경으로 새로 보이게 된 사진은 "색인에 없음"이라 대상이 된다 (변경 번호가 옛 값이어도).
 * - 미디어 DB 가 재구축돼 변경 번호가 바뀌면 그 사진들은 다시 분석된다. 드물고, 결과는 맞다.
 */
class IndexNewPhotosUseCase @Inject constructor(
    private val source: PhotoSource,
    private val vision: VisionEncoder,
    private val index: PhotoIndexRepository,
    private val persons: PersonRepository,
    private val assignPersons: AssignPersonsUseCase,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }) {
        // 한 번 훑는 데 수 분이 걸린다. 그동안 새로 찍었거나 선택에 추가된 사진은 처음 읽은 목록에 없으므로,
        // 끝나면 목록을 다시 읽어 남은 사진이 있으면 이어서 한다.
        // 이번 실행에서 이미 시도한 사진(실패 포함)은 다시 시도하지 않는다 → 실패는 다음 실행에서 재시도.
        val attempted = HashSet<Long>()
        var processed = 0
        for (pass in 1..MAX_PASSES) {
            val n = indexOnce(attempted, onProgress)
            processed += n
            if (n == 0) break
        }
        // 재색인·모델 교체 뒤 중심을 새 벡터 기준으로. 이번에 처리한 게 없어도, 지난 실행이 재계산 전에 끊겼으면
        // 낡음 표시가 남아 있어 여기서 계산된다. 배정보다 먼저: 모델 교체 중 옛 중심이라 건너뛴 인물이 여기서 새 중심을 얻는다.
        persons.recomputeCenters(force = processed > 0)
        // 마지막에 한 번 더: 색인 도중 합치기로 끊긴 얼굴이 미배정으로 돌아왔으면 여기서 다시 배정된다
        assignPersons()
        persons.recomputeCenters(force = false)   // 방금 새 인물이 생겼으면(낡음 표시) 첫 얼굴 중심을 평균으로
        persons.deleteOrphans()
        // 인물 기능이 꺼져 있으면 실행 끝마다 얼굴 데이터를 다시 지운다 (지워져 있으면 아무 일도 없다).
        // 끄는 순간 색인 저장이 겹쳐 얼굴이 다시 들어갔거나, 끄는 도중 앱이 종료돼 삭제가 안 된 경우를 여기서 고친다.
        // 이 실행은 Worker 의 runLock 안이라 다른 색인과 겹치지 않는다.
        if (!settings.facesEnabledNow()) index.deleteAllFaceData()
    }

    /** 얼굴을 분석할 수 있고, 사용자가 켜 둔 상태인가. */
    private fun wantFaces() = vision.facesAvailable && settings.facesEnabledNow()

    /** 한 번 훑기. 분석한 사진 수를 돌려준다. */
    private suspend fun indexOnce(attempted: MutableSet<Long>, onProgress: suspend (Int, Int) -> Unit): Int {
        val scope = settings.albumsNow()        // 이 판을 시작할 때의 앨범 범위
        val refs = source.refs()                 // 조회 실패면 예외 → Worker 가 재시도
        val states = index.indexStates()
        val present = refs.mapTo(HashSet()) { it.mediaId }

        // ponytail: 갤러리가 텅 비어 보이면 지우지 않는다. 진짜로 전부 지웠을 수도 있지만
        // 조회 이상일 가능성이 더 크고, 잘못 지우면 수십 분 재색인이다. 실제 전체 삭제는 다음 사진이 생길 때 정리된다.
        //
        // "선택한 사진만 허용"(부분 접근)에서는 선택 밖 사진도 여기서 지운다. 의도된 선택이다:
        // - 사용자가 접근을 좁혔는데 그 사진들의 얼굴·라벨을 계속 보관하는 것은 맞지 않다.
        // - 남겨 두면 검색 결과에 앱이 열 수 없는 사진이 섞인다 (썸네일이 깨진다).
        // 대가는 전체 허용으로 되돌릴 때의 재색인이다. 이름 붙은 인물은 person 테이블에 중심과 함께 남으므로
        // (deleteOrphans 는 이름 없는 인물만 지운다) 재색인된 얼굴이 가장 가까운 인물로 다시 붙는다.
        // 단 그 사이 INDEX_VERSION 이 바뀌었으면 옛 중심이라 자동으로 붙지 않는다 → 인물 목록에서 합친다.
        // 분석할 앨범에서 뺀 앨범의 사진도 refs() 에 없으므로 같은 이유·같은 경로로 여기서 지운다.
        if (present.isNotEmpty()) index.delete(states.keys - present)

        val targets = refs
            .filter { it.mediaId !in attempted }
            .filter { r ->
                states[r.mediaId].let {
                    it == null || it.generation != r.generation || it.version < INDEX_VERSION ||
                            (wantFaces() && !it.facesDone)   // 기능을 켰거나 얼굴 모델을 넣은 뒤: 얼굴만 빠진 사진
                }
            }
            .sortedByDescending { it.dateTaken }   // 최신 사진부터 검색 가능하게

        var done = 0
        if (targets.isNotEmpty()) onProgress(0, targets.size)   // 시작 즉시 "분석 중" (첫 배치까지 기다리지 않게)
        for (chunk in targets.chunked(BATCH)) {
            // 앨범 범위가 바뀌면 남은 옛 범위 사진은 그만 분석한다. 바꾼 쪽(SetAlbumsUseCase)이 붙여 둔 다음 실행이 새 범위로 다시 훑는다
            if (settings.albumsNow() != scope) break
            val analyzed = chunk.map { analyze(it) }
            // 저장 앞뒤로 표시: 앞 = 저장 커밋 직후 프로세스가 죽어도 표시가 남는다(apply 는 비동기).
            // 뒤 = 그사이 화면의 재계산(합치기 등)이 표시를 지웠어도, 이 배치가 바꾼 연결이 재계산된다.
            persons.markCentersDirty()
            // 분석 도중 인물 기능이 꺼졌으면 이 배치의 얼굴과 얼굴로 만든 태그도 저장하지 않는다 (끄기가 지운 뒤에 다시 생기지 않게)
            index.save(
                if (settings.facesEnabledNow()) analyzed
                else analyzed.map { a ->
                    a.copy(faces = emptyList(), facesDone = false, labels = a.labels.filterNot { it.name in StateTag.FACE_TAGS })
                },
                INDEX_VERSION,
            )
            persons.markCentersDirty()
            // 반대로 분석 중에 켜졌으면: 얼굴 없이 분석한 사진은 facesDone = false 로 저장돼 다음 실행에서 얼굴만 채워진다
            chunk.mapTo(attempted) { it.mediaId }
            assignPersons()                        // 배치마다: 중간에 멈춰도 인물이 생긴다
            done += chunk.size
            onProgress(done, targets.size)
            yield()
        }
        return targets.size
    }

    private suspend fun analyze(ref: PhotoRef): PhotoAnalysis {
        // 1024px + 얼굴 검출 minFaceSize 0.04 (MlVisionEncoder): 단체 사진의 작은 얼굴까지 잡는다.
        // 임베더·라벨러는 내부에서 224 근처로 줄이므로 결과 차이가 없다.
        val frame = source.thumbnail(ref.mediaId, maxSide = 1024) ?: return PhotoAnalysis(ref, failed = true)
        return try {
            // 얼굴 모델이 아직 없거나(MVP) 인물 기능이 꺼져 있으면 얼굴만 건너뛰고 facesDone = false 로 남긴다.
            // 모델을 넣거나 기능을 켜면 그 사진들만 다시 분석된다 (INDEX_VERSION 을 올릴 필요 없음).
            // 모델이 있는데 실패하면(메모리 부족 등 일시 오류) 아래 catch 로 가서 실패로 저장 → 다음 실행에서 재시도.
            // 실패를 "얼굴 없음"으로 삼키면 재색인 때 기존 얼굴·인물 연결이 영영 사라진다.
            val withFaces = wantFaces()
            val faces = if (withFaces) vision.faces(frame) else emptyList()
            PhotoAnalysis(
                ref = ref,
                dHash = dHash(frame),
                scene = vision.scene(frame),
                faces = faces,
                facesDone = withFaces,
                labels = vision.labels(frame) + stateTags(frame, faces),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Exception 만 잡으면 OutOfMemoryError(큰 사진 디코딩·비트맵 복사)가 실행 전체를 실패시킨다.
            // 이 사진 하나의 비트맵은 여기서 버려지므로, 실패로 기록하고 다음 사진으로 넘어간다.
            PhotoAnalysis(ref, failed = true)   // 손상 파일·일시 오류. 다음 실행에서 재시도
        }
    }

    companion object {
        /**
         * 모델·전처리·상태 규칙을 바꾸면 올린다 → 전 사진 재색인.
         * 벡터·인물 중심에도 이 값이 함께 저장되고, 검색·배정은 현재 버전 것만 쓴다.
         * 그래서 차원이 같은 모델로 바꿔도 재색인 중에 옛 공간 벡터와 섞이지 않는다.
         */
        const val INDEX_VERSION = 1
        private const val BATCH = 50
        private const val MAX_PASSES = 3     // 계속 사진이 생겨도 한 실행이 끝없이 돌지 않게
    }
}