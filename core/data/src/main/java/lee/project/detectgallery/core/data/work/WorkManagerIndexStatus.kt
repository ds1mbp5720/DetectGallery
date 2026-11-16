package lee.project.detectgallery.core.data.work

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import lee.project.detectgallery.core.data.db.PhotoIndexDao
import lee.project.detectgallery.core.data.source.MediaStorePhotoSource
import lee.project.detectgallery.core.domain.model.IndexProgress
import lee.project.detectgallery.core.domain.model.LibraryStatus
import lee.project.detectgallery.core.domain.port.IndexStatus
import lee.project.detectgallery.core.domain.usecase.IndexNewPhotosUseCase
import javax.inject.Inject

class WorkManagerIndexStatus @Inject constructor(
    @ApplicationContext context: Context,
    source: MediaStorePhotoSource,
    dao: PhotoIndexDao,
) : IndexStatus {
    // 앱 실행 시 작업만 보면 충전 중·새 사진 색인의 진행률이 안 보인다 → 태그로 셋 다 본다
    override val progress: Flow<IndexProgress?> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(IndexScheduler.TAG).map { infos ->
            infos.asSequence()
                .filter { it.state == WorkInfo.State.RUNNING }
                .map { IndexProgress(it.progress.getInt(IndexPhotosWorker.KEY_DONE, 0), it.progress.getInt(IndexPhotosWorker.KEY_TOTAL, 0)) }
                .filter { it.total > 0 }   // 잠금 대기 중이거나 할 일이 없는 작업은 진행으로 치지 않는다 ("0 / 0" 방지)
                .maxByOrNull { it.total }  // 둘 이상 돌면 실제로 일하는 쪽
        }

    // progress 를 쓰므로 progress 아래에 둔다 (프로퍼티 초기화 순서)
    override val library: Flow<LibraryStatus> =
        combine(source.countFlow(), dao.analyzedCount(IndexNewPhotosUseCase.INDEX_VERSION), progress) { gallery, analyzed, p ->
            LibraryStatus(galleryCount = gallery, analyzedCount = analyzed, indexing = p != null)
        }.distinctUntilChanged()
}