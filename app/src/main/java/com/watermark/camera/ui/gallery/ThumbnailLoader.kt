package com.watermark.camera.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.watermark.camera.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相册缩略图加载器。
 *
 * 旧实现（审计 C6）每次绑定都先 `setImageDrawable(null)` 再异步解码，且没有任何缓存，
 * 快速滚动会闪烁并反复解码同一张图。这里改为：
 * - **内存 LRU 缓存**：同一张缩略图只解码一次；
 * - **按 holder 取消**：滚出屏幕立刻取消在途任务，避免无谓解码；
 * - 解码统一在小尺寸进行（Q+ 用 `loadThumbnail`，更早版本用 `inSampleSize`）。
 */
object ThumbnailLoader {

    private const val TAG = "ThumbnailLoader"

    /** 目标缩略图尺寸（像素）。 */
    private const val TARGET_PX = 288

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 位图缓存上限：可用堆的 1/8。 */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 每个 key 的在途任务，新请求覆盖旧请求时取消旧任务。 */
    private val inFlight = HashMap<String, Job>()

    /**
     * 载入缩略图。
     *
     * @param onReady 在主线程回调；调用方需自行校验 holder 仍绑定同一 uri。
     */
    fun load(context: Context, uri: String, onReady: (Bitmap) -> Unit) {
        cache.get(uri)?.let {
            onReady(it)
            return
        }
        val appContext = context.applicationContext
        inFlight.remove(uri)?.cancel()
        inFlight[uri] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(appContext, uri) }
            if (bitmap != null) {
                cache.put(uri, bitmap)
                withContext(Dispatchers.Main) { onReady(bitmap) }
            }
            inFlight.remove(uri)
        }
    }

    /** 取消某张图的在途加载（holder 回收时调用）。 */
    fun cancel(uri: String) {
        inFlight.remove(uri)?.cancel()
    }

    private fun decode(context: Context, uriString: String): Bitmap? = try {
        val uri = Uri.parse(uriString)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(TARGET_PX, TARGET_PX), null)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_PX)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    } catch (e: Exception) {
        Logger.w(TAG, "缩略图解码失败 $uriString: ${e.message}")
        null
    }

    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}