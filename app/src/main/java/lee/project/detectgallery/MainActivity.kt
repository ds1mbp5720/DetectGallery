package lee.project.detectgallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lee.project.detectgallery.core.ui.theme.DetectGallery

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** 공유로 받아 아직 검색하지 않은 사진(앱 캐시 복사본). 권한 화면을 거쳐도 기다렸다가 검색 탭이 받아 간다 (C9) */
    private var shared by mutableStateOf<String?>(null)

    /** 복사 중인 공유 원본. 복사 도중 화면이 다시 만들어지면(회전) 이어서 복사한다 */
    private var copying: Uri? = null
    private var copyJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shared = savedInstanceState?.getString(KEY_SHARED)
        val resume =
            savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_COPYING, Uri::class.java) }
        when {
            savedInstanceState == null -> sharedImageUri(
                this,
                intent
            )?.let(::receive)   // 회전·복원 때는 같은 공유를 다시 받지 않는다
            resume != null -> receive(resume)                                              // 복사 도중 회전: 이어서
        }
        setContent {
            DetectGallery {
                GalleryAccessGate { access ->
                    AppNavHost(
                        access,
                        sharedUri = shared,
                        onSharedConsumed = ::consumed
                    )
                }
            }
        }
    }

    /** 앱이 떠 있을 때 공유하면 새 화면 대신 여기로 온다 (singleTask). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImageUri(this, intent)?.let(::receive)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SHARED, shared)
        outState.putParcelable(KEY_COPYING, copying)
    }

    /** 새 공유가 오면 앞의 복사는 버린다: 늦게 끝난 옛 복사가 새 사진을 덮지 않게. */
    private fun receive(uri: Uri) {
        copyJob?.cancel()
        copying = uri
        copyJob = lifecycleScope.launch {
            val copy = copySharedImage(this@MainActivity, uri)
            copying = null
            if (copy != null) shared = copy.toString()
            else Toast.makeText(this@MainActivity, "공유한 사진을 열지 못했어요.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 검색 탭이 uri 를 받아 갔다. 그사이 다음 공유가 도착해 shared 가 바뀌었으면 그것은 남겨 둔다(다음에 받아 간다).
     * 받아 간 사진보다 먼저 만든 공유 파일은 이제 화면에 없으니 지운다.
     */
    private fun consumed(uri: String) {
        if (shared == uri) shared = null
        lifecycleScope.launch(Dispatchers.IO) {
            pruneSharedImages(
                this@MainActivity,
                keep = Uri.parse(uri)
            )
        }
    }

    private companion object {
        const val KEY_SHARED = "shared"
        const val KEY_COPYING = "shared-copying"
    }
}