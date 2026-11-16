import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "lee.project.detectgallery.core.ml"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:imaging"))

    implementation(libs.mediapipe.vision)
    implementation(libs.mlkit.labeling)
    implementation(libs.mlkit.face)
    implementation(libs.tflite)
    implementation(libs.coroutines.play.services)  // Task.await()
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.onnxruntime.android)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
}

// ── 모델 자동 다운로드 ──────────────────────────────────────────────
// 모델 파일은 git 에 올리지 않는다 (.gitignore). 빌드할 때 assets 에 없으면 아래 주소에서 받는다.
// 라이브러리를 버전만 적어 두고 Gradle 이 받아 오는 것과 같은 방식이다.
// FaceNet 은 공식 배포 주소가 없어, 공개 저장소의 변환본을 브랜치가 아닌 커밋 번호로 고정한 주소에서 받는다.
// 가중치 라이선스는 명시돼 있지 않다 → 앱을 배포하기 전에 원 출처(deepface Facenet512)를 확인한다.
val downloadModels by tasks.registering {
    description = "assets 에 없는 모델 파일을 내려받는다"

    // 설정 캐시(configuration cache) 오류 방지를 위해 projectDir을 가져옵니다.
    val projectDir = layout.projectDirectory.asFile

    val models: Map<String, Pair<String, String?>> = mapOf(
        "efficientdet_lite0.tflite" to (
                "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite" to null
                ),
        "mobilenet_v3_small.tflite" to (
                "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite" to null
                ),
        // FaceNet-512: 입력 160×160 FLOAT32, 출력 512차원, 전처리는 이미지별 표준화(FaceNet.kt 의 standardize 와 같다).
        "facenet.tflite" to (
                "https://raw.githubusercontent.com/shubham0204/FaceRecognition_With_FaceNet_Android/48493131e12c4c171c7ea531da476428ba5959c8/app/src/main/assets/facenet_512.tflite" to null
                ),
    )

    doLast {
        val modelsDir = File(projectDir, "src/main/assets")
        val lockFile = File(projectDir, "models.lock")

        modelsDir.mkdirs()

        // 상단에 import 문을 추가했으므로 Properties()를 바로 사용합니다.
        val lock = Properties().apply {
            if (lockFile.exists()) {
                lockFile.inputStream().use { load(it) }
            }
        }

        // 모델 하나를 처리할 때마다 저장한다
        fun saveLock() = lockFile.outputStream().use { lock.store(it, "model SHA-256 + URL (downloadModels)") }

        fun sha256(f: File): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(f.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) } // it 충돌 방지를 위해 명시적 매개변수 사용
        }

        models.forEach { (name, spec) ->
            val (url, pinned) = spec
            val target = File(modelsDir, name)

            val lockedUrl = lock.getProperty(name)?.substringAfter(' ', "") ?: ""
            if (lockedUrl != url) {
                if (target.exists()) logger.lifecycle("$name: 주소가 바뀌었거나 출처 기록이 없어 다시 받습니다")
                target.delete()
                if (lock.remove(name) != null) saveLock()
            }

            if (!target.exists()) {
                logger.lifecycle("모델 내려받는 중: $name")
                val part = File(modelsDir, "$name.part")

                // java.net.URI 대신 상단에 import한 URI 사용
                val conn = URI(url).toURL().openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                conn.getInputStream().use { input ->
                    part.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val got = sha256(part)
                val expected = pinned ?: lock.getProperty(name)?.substringBefore(' ')
                if (!expected.isNullOrEmpty() && got != expected) {
                    part.delete()
                    error("$name: 받은 파일의 체크섬이 기록과 다릅니다 ($got). 주소의 내용이 바뀐 것입니다. " +
                            "의도한 변경이면 models.lock 의 $name 줄을 지우고 다시 빌드하세요.")
                }
                check(part.renameTo(target)) { "$name 저장 실패" }
                lock.setProperty(name, "$got $url")
                saveLock()
                logger.lifecycle("$name 해시와 주소를 models.lock 에 기록했습니다. 커밋하세요.")
            } else {
                val actual = sha256(target)
                val expected = pinned ?: lock.getProperty(name)?.substringBefore(' ') ?: ""
                check(actual == expected) {
                    "$name 체크섬 불일치 ($actual). assets 의 $name 을 지우고 다시 빌드하세요."
                }
            }
        }

        if (!File(modelsDir, "facenet.tflite").exists()) {
            logger.warn("facenet.tflite 가 없습니다. 인물 기능 없이 빌드됩니다.")
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadModels) }