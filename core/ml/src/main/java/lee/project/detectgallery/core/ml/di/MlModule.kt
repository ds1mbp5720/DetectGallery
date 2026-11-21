package lee.project.detectgallery.core.ml.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.components.SingletonComponent
import lee.project.detectgallery.core.domain.port.LiveDetector
import lee.project.detectgallery.core.domain.port.TextEncoder
import lee.project.detectgallery.core.domain.port.VisionEncoder
import lee.project.detectgallery.core.ml.LiveDetectorImpl
import lee.project.detectgallery.core.ml.MlVisionEncoder
import lee.project.detectgallery.core.ml.NoTextEncoder

@Module
@InstallIn(SingletonComponent::class)
abstract class MlModule {
    @Binds
    abstract fun vision(impl: MlVisionEncoder): VisionEncoder
    @Binds
    abstract fun text(impl: NoTextEncoder): TextEncoder
}

// LiveDetector 는 LiveViewModel 과 같은 수명(ViewModelComponent)이다. 화면 수명이 아니다: 탭을 바꾸면 백스택 항목이 저장돼(saveState)
// 검출기와 최근 프레임(약 6MB)이 다른 탭에 있는 동안에도 남는다. 메모리가 문제되면 ON_STOP 에서 비우고 지연 생성한다.
@Module
@InstallIn(ViewModelComponent::class)
abstract class LiveModule {
    @Binds abstract fun live(impl: LiveDetectorImpl): LiveDetector
}