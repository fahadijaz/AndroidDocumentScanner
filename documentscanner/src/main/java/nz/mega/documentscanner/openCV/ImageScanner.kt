package nz.mega.documentscanner.openCV

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mega.documentscanner.data.BitmapCropResult
import nz.mega.documentscanner.utils.BitmapUtils
import nz.mega.documentscanner.openCV.OpenCvUtils.yuvToRgba
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

object ImageScanner {

    private const val THRESHOLD_LEVEL = 2
    private const val AREA_LOWER_THRESHOLD = 0.2
    private const val AREA_UPPER_THRESHOLD = 0.98
    private const val DOWNSCALE_IMAGE_SIZE = 600.0

    private val areaDescendingComparator: Comparator<MatOfPoint2f> by lazy {
        Comparator { m1, m2 ->
            val area1 = Imgproc.contourArea(m1)
            val area2 = Imgproc.contourArea(m2)
            ceil(area2 - area1).toInt()
        }
    }

    suspend fun getCroppedImage(
        bitmap: Bitmap,
        providedPoints: List<PointF>? = null
    ): BitmapCropResult? = withContext(Dispatchers.IO) {
        val cropPoints = providedPoints ?: getPoint(bitmap)?.toArray()
            ?.map { PointF(it.x.toFloat(), it.y.toFloat()) }

        if (!cropPoints.isNullOrEmpty()) {
            val croppedBitmap = getScannedBitmap(
                bitmap,
                cropPoints[0].x,
                cropPoints[0].y,
                cropPoints[1].x,
                cropPoints[1].y,
                cropPoints[2].x,
                cropPoints[2].y,
                cropPoints[3].x,
                cropPoints[3].y,
            )

            BitmapCropResult(
                croppedBitmap,
                bitmap.width,
                bitmap.height,
                cropPoints
            )
        } else {
            null
        }
    }

    suspend fun getCropLines(imageProxy: ImageProxy, maxWidth: Float, maxHeight: Float): FloatArray? =
        withContext(Dispatchers.IO) {
            var resultArray: FloatArray? = null
            val imageMat: Mat = imageProxy.yuvToRgba()
            val ratioX = maxWidth / imageMat.width()
            val ratioY = maxHeight / imageMat.height()

            getPoint(imageMat)?.toArray()
                ?.map { point ->
                    PointF(point.x.toFloat() * ratioX, point.y.toFloat() * ratioY)
                }
                ?.let { points ->
                    resultArray = floatArrayOf(
                        points[0].x,
                        points[0].y,
                        points[1].x,
                        points[1].y,
                        points[1].x,
                        points[1].y,
                        points[2].x,
                        points[2].y,
                        points[2].x,
                        points[2].y,
                        points[3].x,
                        points[3].y,
                        points[3].x,
                        points[3].y,
                        points[0].x,
                        points[0].y,
                    )
                }

            imageMat.release()

            resultArray
        }

    private fun getScannedBitmap(
        bitmap: Bitmap?,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        x4: Float,
        y4: Float
    ): Bitmap {
        val perspective = PerspectiveTransformation()
        val rectangle = MatOfPoint2f()
        rectangle.fromArray(
            Point(x1.toDouble(), y1.toDouble()), Point(
                x2.toDouble(), y2.toDouble()
            ), Point(x3.toDouble(), y3.toDouble()), Point(x4.toDouble(), y4.toDouble())
        )
        val dstMat = perspective.transform(BitmapUtils.bitmapToMat(bitmap!!), rectangle)
        val resultBitmap = BitmapUtils.matToBitmap(dstMat)
        dstMat.release()
        return resultBitmap
    }

    private fun getPoint(bitmap: Bitmap): MatOfPoint2f? {
        val mat = BitmapUtils.bitmapToMat(bitmap)
        return getPoint(mat)
    }

    private fun getPoint(src: Mat): MatOfPoint2f? {
        // Downscale image for better performance.
        val ratio = DOWNSCALE_IMAGE_SIZE / max(src.width(), src.height())
        val downscaledSize = Size(src.width() * ratio, src.height() * ratio)
        val downscaled = Mat(downscaledSize, src.type())
        Imgproc.resize(src, downscaled, downscaledSize)
        val rectangles = getPoints(downscaled)
        if (rectangles.isEmpty()) {
            return null
        }
        Collections.sort(rectangles, areaDescendingComparator)
        val largestRectangle = rectangles[0]
        return OpenCvUtils.scaleRectangle(largestRectangle, 1f / ratio)
    }

    private fun getPoints(src: Mat): List<MatOfPoint2f> {

        // Blur the image to filter out the noise.
        val blurred = Mat()
        Imgproc.medianBlur(src, blurred, 9)

        // Set up images to use.
        val gray0 = Mat(blurred.size(), CvType.CV_8U)
        val gray = Mat()

        // For Core.mixChannels.
        val contours: List<MatOfPoint> = ArrayList()
        val rectangles: MutableList<MatOfPoint2f> = ArrayList()
        val sources: MutableList<Mat> = ArrayList()
        sources.add(blurred)
        val destinations: MutableList<Mat> = ArrayList()
        destinations.add(gray0)

        // To filter rectangles by their areas.
        val srcArea = src.rows() * src.cols()

        // Find squares in every color plane of the image.
        for (c in 0..2) {
            val ch = intArrayOf(c, 0)
            val fromTo = MatOfInt(*ch)
            Core.mixChannels(sources, destinations, fromTo)

            // Try several threshold levels.
            for (l in 0 until THRESHOLD_LEVEL) {
                if (l == 0) {
                    // HACK: Use Canny instead of zero threshold level.
                    // Canny helps to catch squares with gradient shading.
                    // NOTE: No kernel size parameters on Java API.
                    Imgproc.Canny(gray0, gray, 10.0, 20.0)

                    // Dilate Canny output to remove potential holes between edge segments.
                    Imgproc.dilate(gray, gray, Mat.ones(Size(3.0, 3.0), 0))
                } else {
                    val threshold = (l + 1) * 255 / THRESHOLD_LEVEL
                    Imgproc.threshold(
                        gray0,
                        gray,
                        threshold.toDouble(),
                        255.0,
                        Imgproc.THRESH_BINARY
                    )
                }

                // Find contours and store them all as a list.
                Imgproc.findContours(
                    gray,
                    contours,
                    Mat(),
                    Imgproc.RETR_LIST,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )
                for (contour in contours) {
                    val contourFloat = OpenCvUtils.toMatOfPointFloat(
                        contour
                    )
                    val arcLen = Imgproc.arcLength(contourFloat, true) * 0.02

                    // Approximate polygonal curves.
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(contourFloat, approx, arcLen, true)
                    if (isRectangle(approx, srcArea)) {
                        rectangles.add(approx)
                    }
                }
            }
        }
        return rectangles
    }

    private fun isRectangle(polygon: MatOfPoint2f, srcArea: Int): Boolean {
        val polygonInt = OpenCvUtils.toMatOfPointInt(polygon)
        if (polygon.rows() != 4) {
            return false
        }
        val area = abs(Imgproc.contourArea(polygon))
        if (area < srcArea * AREA_LOWER_THRESHOLD || area > srcArea * AREA_UPPER_THRESHOLD) {
            return false
        }
        if (!Imgproc.isContourConvex(polygonInt)) {
            return false
        }

        // Check if the all angles are more than 72.54 degrees (cos 0.3).
        var maxCosine = 0.0
        val approxPoints = polygon.toArray()
        for (i in 2..4) {
            val cosine = abs(
                OpenCvUtils.angle(
                    approxPoints[i % 4], approxPoints[i - 2], approxPoints[i - 1]
                )
            )
            maxCosine = max(cosine, maxCosine)
        }
        return maxCosine < 0.3
    }
}
