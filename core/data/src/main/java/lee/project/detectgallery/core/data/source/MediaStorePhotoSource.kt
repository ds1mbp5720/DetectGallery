package lee.project.detectgallery.core.data.source

import android.content.ContentUris
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import lee.project.detectgallery.core.domain.model.ImageFrame
import lee.project.detectgallery.core.domain.model.PhotoRef
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.imaging.toImageFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.database.ContentObserver
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import lee.project.detectgallery.core.domain.model.Album
import lee.project.detectgallery.core.domain.port.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest

/** minSdk 30 전제 (GENERATION_MODIFIED 열). */
class MediaStorePhotoSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,   // 분석할 앨범 (비어 있으면 모든 앨범)
) : PhotoSource {
    private val resolver = context.contentResolver

    // 내부 저장소만. 변경 번호는 볼륨마다 따로 센다. SD 카드까지 넣으려면 (볼륨, id) 를 키로 바꾼다.
    private val collection: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 1만 장이면 1만 행 조회. 수십 ms 수준이라 매 실행 전체를 읽는다. */
    override suspend fun refs(): List<PhotoRef> = withContext(Dispatchers.IO) {
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
        // null = 미디어 프로바이더 일시 이상. 빈 목록으로 바꾸면 색인 전체가 "삭제된 사진" 이 된다.
        val (where, args) = albumFilter(settings.albumsNow())   // 고른 앨범 밖의 사진은 "사라진 사진" 이 되어 색인에서 지워진다
        val cursor = resolver.query(collection, cols, where, args, null)
            ?: throw IllegalStateException("MediaStore 조회 실패")
        cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val taken = c.getLong(1).takeIf { it > 0 }
                        ?: (c.getLong(2) * 1000)  // DATE_TAKEN 없으면 추가 시각
                    add(PhotoRef(c.getLong(0), taken, c.getLong(3)))
                }
            }
        }
    }

    /**
     * 분석 대상 사진 수. refs() 와 같은 조건(collection·고른 앨범)으로 세므로 부분 접근이면 고른 사진만 센다.
     * 촬영·삭제·선택 변경 때 MediaStore 가 알려 주면 다시 센다. 조회가 실패(null)한 순간은 값을 내지 않는다.
     * 고른 앨범이 바뀌면 그 범위로 바로 다시 센다(refs() 와 같은 조건).
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun countFlow(): Flow<Int> = settings.albums
        .flatMapLatest { ids ->
            changes().debounce(500).mapNotNull { count(ids) }
        }   // 여러 장을 한꺼번에 옮기면 알림이 연달아 온다
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /** MediaStore 변경 알림. 구독하자마자 한 번 낸다. */
    private fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {          // Handler 없음 → 바인더 스레드에서 불린다
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        trySend(Unit)                                            // 처음 한 번
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun count(albums: Set<Long>): Int? {
        val (where, args) = albumFilter(albums)
        return resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), where, args, null)
            ?.use { it.count }
    }

    /** 고른 앨범만 거르는 조건. 비어 있으면 조건 없음(모든 앨범). */
    private fun albumFilter(ids: Set<Long>): Pair<String?, Array<String>?> =
        if (ids.isEmpty()) null to null
        else "${MediaStore.Images.Media.BUCKET_ID} IN (${ids.joinToString(",") { "?" }})" to ids.map(
            Long::toString
        ).toTypedArray()

    /** 앨범 목록(고르는 화면용). 앨범 선택과 무관하게 볼 수 있는 사진 전부를 센다. 최신 사진 순이라 처음 본 사진이 대표 사진. */
    override suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        val cols = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media._ID
        )
        val cursor = resolver.query(
            collection,
            cols,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
            ?: throw IllegalStateException("MediaStore 조회 실패")
        cursor.use { c ->
            val byId = LinkedHashMap<Long, Album>()
            while (c.moveToNext()) {
                if (c.isNull(0)) continue   // 폴더 없는 사진(드묾): BUCKET_ID IN (…) 로 거를 수 없어 목록에서 뺀다
                val id = c.getLong(0)
                val a = byId[id]
                byId[id] = a?.copy(count = a.count + 1) ?: Album(
                    id,
                    c.getString(1) ?: "이름 없는 앨범",
                    1,
                    c.getLong(2)
                )
            }
            byId.values.sortedByDescending { it.count }
        }
    }

    /**
     * 원본을 maxSide 로 줄여 디코딩한다. loadThumbnail 은 쓰지 않는다:
     * 요청 크기와 무관하게 시스템이 캐시한 썸네일(기기에 따라 512px 안팎)을 돌려주고 키우지는 않아서,
     * 1024px 를 요청해도 단체 사진의 작은 얼굴이 40px 하한 아래로 떨어진다.
     * ImageDecoder 의 setTargetSize 는 JPEG 를 축소 디코딩하므로 원본 전체를 펼치지 않는다. EXIF 회전도 반영한다.
     */
    override suspend fun thumbnail(mediaId: Long, maxSide: Int): ImageFrame? =
        decode(ContentUris.withAppendedId(collection, mediaId), maxSide)

    override suspend fun load(uri: String, maxSide: Int): ImageFrame? =
        decode(Uri.parse(uri), maxSide)

    private suspend fun decode(uri: Uri, maxSide: Int): ImageFrame? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE        // HARDWARE 비트맵은 getPixels 불가
                val s = info.size
                val scale = maxSide.toFloat() / maxOf(s.width, s.height)
                if (scale < 1f) d.setTargetSize(
                    (s.width * scale).toInt(),
                    (s.height * scale).toInt()
                )
            }.toImageFrame(maxSide)
        }.getOrNull()
    }
}