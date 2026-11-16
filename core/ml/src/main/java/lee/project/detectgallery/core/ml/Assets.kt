package lee.project.detectgallery.core.ml

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** build.gradle 의 noCompress 가 없으면 openFd 에서 예외가 난다. */
internal fun Context.loadMappedAsset(name: String): MappedByteBuffer =
    assets.openFd(name).use { fd ->
        FileInputStream(fd.fileDescriptor).use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }