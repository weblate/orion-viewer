package universe.constellation.orion.viewer.bitmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import universe.constellation.orion.viewer.BitmapCache
import universe.constellation.orion.viewer.document.Page
import universe.constellation.orion.viewer.layout.LayoutPosition
import universe.constellation.orion.viewer.log
import kotlin.coroutines.coroutineContext
import kotlin.math.min

data class PagePart(val absPartRect: Rect) {

    @Volatile
    var bitmapInfo: BitmapCache.CacheInfo? = null

    @Volatile
    internal var isActive: Boolean = false

    //access from UI thread
    private val localPartRect = Rect(0, 0, absPartRect.width(), absPartRect.height())

    //access from renderer thread
    private val rendTmp = Rect()

    @Volatile
    private var nonRenderedPart = Region(absPartRect)
    private var nonRenderedPartTmp = Region(absPartRect)

    @Volatile
    private var opMarker = 1

    suspend fun render(
        requestedArea: Rect,
        zoom: Double,
        cropLeft: Int,
        cropTop: Int,
        page: Page
    ) {
        if (!isActive) return

        val info: BitmapCache.CacheInfo
        val marker = synchronized(this) {
            if (nonRenderedPart.isEmpty || !coroutineContext.isActive) return
            //TODO add busy flag
            info = this.bitmapInfo ?: return
            opMarker
        }

        info.mutex.lock()
        try {
            //TODO: join bitmap
            rendTmp.set(absPartRect)
            if (rendTmp.intersect(requestedArea)) {
                nonRenderedPartTmp.set(nonRenderedPart)
                if (nonRenderedPartTmp.op(rendTmp, Region.Op.INTERSECT)) {
                    rendTmp.set(nonRenderedPartTmp.bounds)
                    rendTmp.offset(-absPartRect.left, -absPartRect.top)
                    renderInner(
                        rendTmp,
                        zoom,
                        absPartRect.left + cropLeft,
                        absPartRect.top + cropTop,
                        page,
                        info.bitmap
                    )
                    synchronized(this) {
                        if (opMarker == marker) {
                            rendTmp.offset(absPartRect.left, absPartRect.top)
                            nonRenderedPart.op(rendTmp, Region.Op.DIFFERENCE)
                        }
                    }
                }
            }
        } finally {
            info.mutex.unlock()
        }
    }

    fun draw(canvas: Canvas, pageVisiblePart: Rect, defaultPaint: Paint) {
        if (!isActive || bitmapInfo == null) return
        if (Rect.intersects(absPartRect, pageVisiblePart)) {
            canvas.drawBitmap(bitmapInfo!!.bitmap, localPartRect, absPartRect, defaultPaint)
        }
    }

    internal fun activate(bitmapCache: BitmapCache, partWidth: Int, partHeight: Int) {
        assert(!isActive)
        synchronized(this) {
            isActive = true
            opMarker++
            bitmapInfo = bitmapCache.createBitmap(partWidth, partHeight)
            nonRenderedPart.set(absPartRect)
        }
    }

    internal fun deactivate(bitmapCache: BitmapCache) {
        synchronized(this) {
            isActive = false
            opMarker++
            bitmapCache.free(bitmapInfo ?: return)
        }
    }
}

class FlexibleBitmap(width: Int, height: Int, val partWidth: Int, val partHeight: Int, val pageNum: Int = -1) {

    constructor(initialArea: Rect, partWidth: Int, partHeight: Int) : this(
        initialArea.width(),
        initialArea.height(),
        partWidth,
        partHeight
    )

    private var renderingArea = Rect(0, 0, width, height)

    @Volatile
    var data = initData(renderingArea.width(), renderingArea.height())
        private set

    val width: Int
        get() = renderingArea.width()

    val height: Int
        get() = renderingArea.height()

    private fun initData(width: Int, height: Int): Array<Array<PagePart>> {
        log("FB: initData $pageNum $width $height")
        val rowCount = height.countCells(partHeight)
        return Array(rowCount) { row ->
            val colCount = width.countCells(partWidth)
            Array(colCount) { col ->
                val left = partWidth * col
                val top = partHeight * row
                val partWidth = min(width - left, partWidth)
                val partHeight = min(height - top, partHeight)
                PagePart(
                    Rect(
                        left,
                        top,
                        left + partWidth,
                        top + partHeight
                    )
                )
            }
        }
    }

    fun resize(width: Int, height: Int, bitmapCache: BitmapCache): FlexibleBitmap {
        log("FB: resize $pageNum $width $height")
        free(bitmapCache)
        data = initData(width, height)
        renderingArea.set(0, 0, width, height)

        return this
    }

    fun enableAll(cache: BitmapCache) {
        updateDrawAreaAndUpdateNonRenderingPart(renderingArea, cache)
    }

    fun disableAll(cache: BitmapCache) {
        updateDrawAreaAndUpdateNonRenderingPart(Rect(-1, -1, -1, -1), cache)
    }

    fun updateDrawAreaAndUpdateNonRenderingPart(activationRect: Rect, cache: BitmapCache) {
        //free out of view bitmaps
        forEach {
            val newState = Rect.intersects(absPartRect, activationRect)
            if (isActive && !newState) {
                deactivate(cache)
            }
        }
        //then enable visible ones
        forEach {
            val newState = Rect.intersects(absPartRect, activationRect)
            if (!isActive && newState) {
                activate(cache, partWidth, partHeight)
            }
        }
    }

    @TestOnly
    suspend fun renderFull(zoom: Double, page: Page) {
        coroutineScope {
            forEach {
                launch {
                    render(
                        renderingArea,
                        zoom,
                        0,
                        0,
                        page,
                    )
                }
            }
        }
    }

    suspend fun render(renderingArea: Rect, curPos: LayoutPosition, page: Page) {
        log("FB rendering $page $renderingArea")
        coroutineScope {
            forEach {
                launch {
                    render(
                        renderingArea,
                        curPos.docZoom,
                        curPos.x.marginLeft,
                        curPos.y.marginLeft,
                        page,
                    )
                }
            }
        }
    }

    fun forAllTest(body: PagePart.() -> Unit) {
        forEach(body)
    }

    private inline fun forEach(body: PagePart.() -> Unit) {
        this.data.forEach {
            it.forEach { part -> part.body() }
        }
    }

    fun draw(canvas: Canvas, srcRect: Rect, defaultPaint: Paint) {
        forEach { draw(canvas, srcRect, defaultPaint) }
    }

    fun free(cache: BitmapCache) {
        forEach { deactivate(cache) }
    }

    @TestOnly
    fun bitmaps(): List<Bitmap> {
        val res = mutableListOf<Bitmap>()
        forEach { bitmapInfo?.bitmap?.let { res.add(it) } }
        return res
    }
}

fun Int.countCells(cellSize: Int): Int {
    if (this == 0) return 0
    return (this - 1) / cellSize + 1
}

private fun renderInner(bound: Rect, zoom: Double, offsetX: Int, offsetY: Int, page: Page, bitmap: Bitmap): Bitmap {
    log("Rendering $page: $bound\n $offsetX $offsetY\nbm=${bitmap.width}x${bitmap.height}\ndim${bound.width()} ${bound.height()}")
    page.renderPage(
        bitmap,
        zoom,
        bound.left,
        bound.top,
        bound.right,
        bound.bottom,
        offsetX,
        offsetY

    )
    return bitmap
}