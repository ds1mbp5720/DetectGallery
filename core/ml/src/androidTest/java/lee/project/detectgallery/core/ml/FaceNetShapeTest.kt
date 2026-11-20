package lee.project.detectgallery.core.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

@RunWith(AndroidJUnit4::class)
class FaceNetShapeTest {
    @Test
    fun 입력_출력_모양_출력() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val fd = ctx.assets.openFd("facenet.tflite")
        val buf = FileInputStream(fd.fileDescriptor).use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        Interpreter(buf).use { i ->
            Log.i("FaceNet", "input=${i.getInputTensor(0).shape().contentToString()} ${i.getInputTensor(0).dataType()}")
            Log.i("FaceNet", "output=${i.getOutputTensor(0).shape().contentToString()}")
        }
    }
}