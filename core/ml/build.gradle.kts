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
val downloadModels by tasks.registering {
    description = "assets 에 없는 모델 파일을 내려받는다"
    val projectDir = layout.projectDirectory.asFile

    doLast {
        val modelsDir = File(projectDir, "src/main/assets")
        val lockFile = File(projectDir, "models.lock")

        val models: Map<String, Pair<String, String?>> = mapOf(
            "efficientdet_lite0.tflite" to (
                    "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite" to null
                    ),
            "mobilenet_v3_small.tflite" to (
                    "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite" to null
                    ),
        )

        modelsDir.mkdirs()

        // 상단에 import 문을 추가했으므로 Properties()를 바로 사용할 수 있습니다.
        val lock = Properties().apply {
            if (lockFile.exists()) {
                lockFile.inputStream().use { load(it) }
            }
        }
        var lockChanged = false

        // 충돌 방지를 위해 반복문 변수를 name, spec 대신 modelName, modelSpec으로 명시
        models.forEach { (modelName, modelSpec) ->
            val (url, pinned) = modelSpec
            val target = File(modelsDir, modelName)

            if (!target.exists()) {
                logger.lifecycle("모델 내려받는 중: $modelName")
                val part = File(modelsDir, "$modelName.part")

                // URI 및 외부 스트림 처리
                URI(url).toURL().openStream().use { input ->
                    part.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                check(part.renameTo(target)) { "$modelName 저장 실패" }
            }

            // MessageDigest를 통한 SHA-256 해시 계산
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(target.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) } // it 충돌 방지를 위해 명시적 매개변수 사용

            val expected = pinned ?: lock.getProperty(modelName)

            if (expected.isNullOrEmpty()) {
                lock.setProperty(modelName, actual)
                lockChanged = true
                logger.lifecycle("$modelName 해시를 models.lock 에 기록했습니다. 커밋하세요.")
            } else {
                check(actual == expected) { "$modelName 체크섬 불일치 ($actual). 파일을 지우고 다시 받거나, 의도한 교체면 models.lock 의 줄을 지우세요." }
            }
        }

        if (lockChanged) {
            lockFile.outputStream().use { outputStream ->
                lock.store(outputStream, "model SHA-256 (downloadModels)")
            }
        }

        if (!File(modelsDir, "facenet.tflite").exists()) {
            logger.warn("facenet.tflite 가 없습니다. 인물 기능 없이 빌드됩니다 (README 의 FaceNet 안내 참고).")
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }
// 처음 빌드할 때만 인터넷이 필요하다. 받은 뒤에는 파일이 있으면 건너뛴다.