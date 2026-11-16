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

// LiveDetector 는 화면 수명이라 ViewModelComponent 에 둔다
@Module
@InstallIn(ViewModelComponent::class)
abstract class LiveModule {
    @Binds abstract fun live(impl: LiveDetectorImpl): LiveDetector
}