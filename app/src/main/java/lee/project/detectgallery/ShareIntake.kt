package lee.project.detectgallery

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 공유로 받는 사진 한 장의 상한. 넘으면 복사를 멈춘다(끝없는 입력으로 저장 공간이 차지 않게). 원본 사진은 보통 10MB 안쪽. */
private const val MAX_SHARED_BYTES = 50L * 1024 * 1024

/**
 * 다른 앱이 공유한 사진 한 장(ACTION_SEND, image)의 URI. 받지 않을 것이면 null.
 * 여러 장(SEND_MULTIPLE)은 받지 않는다: 사진으로 찾기의 기준은 한 장이다(매니페스트에도 없다).
 * 최근 앱에서 다시 연 경우는 처음 공유 intent 가 그대로 다시 오므로 받지 않는다(같은 사진을 또 찾지 않게).
 */

fun sharedImageUri(context: Context, intent: Intent): Uri? {
if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri   // EXTRA_STREAM 없이 clipData 로만 주는 앱
?: return null
if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
// 이 앱의 provider (androidx-startup 등). "0@app.gallerylens…" 처럼 사용자 번호를 붙여도 같은 provider 로 가므로 떼고 본다
if (uri.authority?.substringAfter('@')?.startsWith(context.packageName) == true) return null
return uri
}

/**
 * 공유받은 사진을 앱 캐시로 복사해 그 file:// URI 를 돌려준다. 실패하거나 상한을 넘으면 null.
 * 공유 URI 의 읽기 권한은 받은 화면이 살아 있는 동안만이라, 그대로 쓰면 화면이 다시 만들어진 뒤
 * 카드 썸네일·기준 바꾸기(다시 읽기)가 실패한다. 원본 바이트를 그대로 옮겨 EXIF 회전도 남는다.
 * 파일 이름이 매번 달라(createTempFile) Coil 캐시가 이전 공유 사진을 보여 주지 않는다. 이전 파일은 지우지 않는다(pruneSharedImages).
 * 취소되면(새 공유가 왔거나 화면이 닫힘) 만들다 만 파일을 지우고 취소를 그대로 올린다.
*/
suspend fun copySharedImage(context: Context, uri: Uri): Uri? = withContext(Dispatchers.IO) {
var out: File? = null
try {
// 파일 만들기도 try 안: 저장 공간이 없으면 여기서 IOException → null (밖으로 던지면 앱이 죽는다)
val dir = File(context.cacheDir, "shared").apply { mkdirs() }
val file = File.createTempFile("query-", null, dir).also { out = it }
checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
file.outputStream().use { output ->
val buf = ByteArray(64 * 1024)
var total = 0L
while (true) {
ensureActive()
val n = input.read(buf)
if (n < 0) break
total += n
if (total > MAX_SHARED_BYTES) throw IOException("공유 사진이 상한보다 큼")
output.write(buf, 0, n)
}
}
}
Uri.fromFile(file)
} catch (e: CancellationException) {
out?.delete()
throw e
} catch (e: Exception) {
out?.delete()
null
}
}

/**
 * keep 보다 먼저 만든 공유 사진 파일을 지운다. 검색 탭이 keep 을 받아 간 뒤에 부른다:
 * 그 전에 지우면 새 사진을 읽지 못했을 때 화면에 남은 이전 카드가 깨진다. keep 뒤에 만든 파일(이어 온 공유를 복사 중)은 남긴다.
 * ponytail: 순서를 파일 시각(mtime)으로 가린다. 그사이 기기 시계가 뒤로 가면 복사 중 파일이 지워질 수 있다(매우 드묾) — 생기면 이름에 순번을.
*/
fun pruneSharedImages(context: Context, keep: Uri) {
val keepFile = File(keep.path ?: return)
val dir = File(context.cacheDir, "shared")
dir.listFiles()?.filter { it != keepFile && it.lastModified() < keepFile.lastModified() }?.forEach { it.delete() }
}