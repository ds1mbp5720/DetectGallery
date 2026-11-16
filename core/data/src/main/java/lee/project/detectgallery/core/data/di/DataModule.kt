package lee.project.detectgallery.core.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import lee.project.detectgallery.core.data.db.AppDatabase
import lee.project.detectgallery.core.data.repo.PrefsSettingsRepository
import lee.project.detectgallery.core.data.repo.RoomPersonRepository
import lee.project.detectgallery.core.data.repo.RoomPhotoIndexRepository
import lee.project.detectgallery.core.data.source.MediaStorePhotoSource
import lee.project.detectgallery.core.data.work.WorkManagerIndexStatus
import lee.project.detectgallery.core.data.work.WorkManagerIndexTrigger
import lee.project.detectgallery.core.domain.port.IndexStatus
import lee.project.detectgallery.core.domain.port.IndexTrigger
import lee.project.detectgallery.core.domain.port.PersonRepository
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.domain.port.QueryVocabulary
import lee.project.detectgallery.core.domain.port.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun photoSource(impl: MediaStorePhotoSource): PhotoSource
    @Binds
    abstract fun index(impl: RoomPhotoIndexRepository): PhotoIndexRepository
    @Binds
    abstract fun vocabulary(impl: RoomPhotoIndexRepository): QueryVocabulary
    @Binds
    abstract fun persons(impl: RoomPersonRepository): PersonRepository
    @Binds
    abstract fun indexStatus(impl: WorkManagerIndexStatus): IndexStatus
    @Binds
    abstract fun settings(impl: PrefsSettingsRepository): SettingsRepository
    @Binds
    abstract fun indexTrigger(impl: WorkManagerIndexTrigger): IndexTrigger

    companion object {
        @Provides
        @Singleton
        fun db(@ApplicationContext c: Context) = AppDatabase.build(c)
        @Provides
        fun photoIndexDao(db: AppDatabase) = db.photoIndexDao()
        @Provides
        fun personDao(db: AppDatabase) = db.personDao()
    }
}
