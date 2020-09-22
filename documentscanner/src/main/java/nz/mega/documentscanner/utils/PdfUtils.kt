package nz.mega.documentscanner.utils

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mega.documentscanner.data.Document
import java.io.FileOutputStream

object PdfUtils {

    suspend fun Document.generatePdf(context: Context): Uri =
        withContext(Dispatchers.IO) {
            require(pages.isNotEmpty()) { "Empty pages" }

            val pdfDocument = PdfDocument()
            val backgroundPaint = Paint().apply { color = Color.WHITE }

            pages.forEachIndexed { index, page ->
                val pageFile = (page.croppedImageUri ?: page.originalImageUri).toFile()

                val bitmap = Glide.with(context)
                    .asBitmap()
                    .load(pageFile)
                    .encodeQuality(quality.value)
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .submit()
                    .get()

                val pdfPage = pdfDocument.startPage(
                    PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index).create()
                )

                pdfPage.canvas.apply {
                    drawPaint(backgroundPaint)
                    drawBitmap(bitmap, 0f, 0f, null)
                }

                pdfDocument.finishPage(pdfPage)

                bitmap.recycle()
            }

            val documentFile = FileUtils.createDocumentFile(context, title)

            FileOutputStream(documentFile).apply {
                pdfDocument.writeTo(this)
                flush()
                close()
            }

            pdfDocument.close()

            documentFile.toUri()
        }
}
