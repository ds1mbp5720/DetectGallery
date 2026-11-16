package lee.project.detectgallery.core.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

@Database(
    entities = [PhotoEntity::class, SceneEmbeddingEntity::class, FaceEntity::class, PersonEntity::class,
        PhotoLabelEntity::class, LabelAliasEntity::class],
    version = 1,
    exportSchema = true,          // core/data/schemas/ 에 버전별 스키마. 마이그레이션 테스트의 기준
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoIndexDao(): PhotoIndexDao
    abstract fun personDao(): PersonDao

    companion object {
        /**
         * 스키마를 바꿀 때마다 하나씩 추가한다. person·face.personId·face.excluded 는 사용자 데이터라
         * 반드시 옮긴다 (fallbackToDestructiveMigration 금지). 예:
         *
         *   val MIGRATION_1_2 = object : Migration(1, 2) {
         *       override fun migrate(db: SupportSQLiteDatabase) {
         *           db.execSQL("ALTER TABLE person ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
         *       }
         *   }
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "gallery-index.db")
                .addMigrations(*MIGRATIONS)
                .addCallback(object : Callback() {
                    // onCreate 가 아니라 onOpen: 앱 업데이트로 바뀐 사전이 기존 사용자에게도 반영되게
                    override fun onOpen(db: SupportSQLiteDatabase) = syncAliases(context, db)
                })
                .build()

        /** label_alias 를 JSON 과 똑같이 맞춘다. 수십 행이라 열 때마다 해도 수 ms. */
        private fun syncAliases(context: Context, db: SupportSQLiteDatabase) {
            val json = JSONObject(context.assets.open("label_alias.json").bufferedReader().readText())
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM label_alias")
                for (alias in json.keys()) {
                    val labels = json.getJSONArray(alias)
                    for (i in 0 until labels.length()) {
                        db.execSQL("INSERT OR IGNORE INTO label_alias(alias, label) VALUES (?, ?)", arrayOf(alias, labels.getString(i)))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}