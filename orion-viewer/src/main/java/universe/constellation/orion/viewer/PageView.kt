package universe.constellation.orion.viewer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import universe.constellation.orion.viewer.bitmap.FlexibleBitmap
import universe.constellation.orion.viewer.document.Document
import universe.constellation.orion.viewer.geometry.RectF
import universe.constellation.orion.viewer.layout.LayoutPosition
import universe.constellation.orion.viewer.view.OrionDrawScene
import universe.constellation.orion.viewer.view.PageLayoutManager
import universe.constellation.orion.viewer.view.precache
import kotlin.coroutines.coroutineContext

enum class PageState(val interactWithUUI: Boolean) {
    STUB(false),
    SIZE_AND_BITMAP_CREATED(true),
    CAN_BE_DELETED(false),
    DESTROYED(false)
}

interface PageInitListener {

    fun onPageInited(pageView: PageView)
}

val handler = CoroutineExceptionHandler { _, ex ->
    log("Bitmap rendering cancelled")
    ex.printStackTrace()
    //TODO processing
}

class PageView(
    val pageNum: Int,
    val document: Document,
    val controller: Controller,
    val rootJob: Job,
    val pageLayoutManager: PageLayoutManager
) {

    val layoutData: LayoutData = LayoutData().apply {
        wholePageRect.set(pageLayoutManager.defaultSize())
    }

    val wholePageRect
        get() = layoutData.wholePageRect

    val width: Int
        get() = wholePageRect.width()
    val height: Int
        get() = wholePageRect.height()

    internal var scene: OrionDrawScene? = null

    private var pageJobs = SupervisorJob(rootJob)

    @Volatile
    var bitmap: FlexibleBitmap? = null

    //TODO reset on lp changes
    @Volatile
    var state: PageState = PageState.STUB

    @Volatile
    var marker: Int = 1

    internal val page = document.getOrCreatePageAdapter(pageNum)

    private val renderedRegion = Rect()
    internal val nonRenderedRegion = Region()
    internal  val tempRegion = Region()
    init {
        wholePageRect.set(pageLayoutManager.defaultSize())
    }

    val layoutInfo: LayoutPosition = LayoutPosition()

    @Volatile
    lateinit var pageInfo: Deferred<PageView>

    fun init() {
       reinit()
    }

    private fun markAsDeattached() {
        //TODO optimize canceling state
        log("Deattached $pageNum")
        state = PageState.CAN_BE_DELETED
        pageJobs.cancelChildren()
        bitmap?.apply {
            this.free(controller.bitmapCache)
            bitmap = null
        }
    }

    fun destroy() {
        log("Destroy $pageNum")
        assert(state == PageState.CAN_BE_DELETED) {"Wrong state calling destroy: $state"}
        markAsDeattached()
        state = PageState.DESTROYED
        pageJobs.cancel()
        GlobalScope.launch(controller.context) {
            pageJobs.cancelAndJoin()
            freePagePointer()
        }
    }

    private fun freePagePointer() {
        page.destroy()
    }

    fun toInvisibleState() {
        markAsDeattached()
    }

    fun reinit() {
        if (state == PageState.SIZE_AND_BITMAP_CREATED) return
        log("Page $pageNum reinit $state $document" )
        pageJobs.cancelChildren()
        pageInfo = GlobalScope.async(controller.context + pageJobs + handler) {
            controller.layoutStrategy.reset(layoutInfo, page)
            if (isActive) {
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        initBitmap(layoutInfo)
                    }
                }
            }
            this@PageView
        }
    }

    private fun initBitmap(layoutInfo: LayoutPosition) {
        if (state == PageState.SIZE_AND_BITMAP_CREATED) return
        val oldSize = Rect(wholePageRect)
        wholePageRect.set(0, 0, layoutInfo.x.pageDimension, layoutInfo.y.pageDimension)
        nonRenderedRegion.set(wholePageRect)
        renderedRegion.set(0, 0, 0, 0)
        bitmap = bitmap?.resize(wholePageRect.width(), wholePageRect.height(), controller.bitmapCache)
            ?: pageLayoutManager.bitmapManager.createDefaultBitmap(Rect(wholePageRect))
        log("PageView.initBitmap $pageNum ${controller.document}: $nonRenderedRegion")
        state = PageState.SIZE_AND_BITMAP_CREATED
        pageLayoutManager.onPageSizeCalculated(this, oldSize)
    }

    fun draw(canvas: Canvas, scene: OrionDrawScene) {
        if (state != PageState.STUB && bitmap!= null) {
            //draw bitmap
            log("Draw page $pageNum in state $state ${bitmap?.width} ${bitmap?.height} ")
            draw(canvas, bitmap!!, scene.defaultPaint!!, scene)
        } else {
            log("Draw border $pageNum in state $state: ${layoutData} on screen ${layoutData.occupiedScreenPartInTmp(scene.pageLayoutManager!!.sceneRect)}")
            drawBorder(canvas, scene)
        }
        scene.orionStatusBarHelper.onPageUpdate(layoutInfo)
    }

    internal fun renderVisibleAsync() {
        GlobalScope.launch (Dispatchers.Main + handler) {
            renderVisible()
        }
    }

    fun occupiedVisiblePartInNewRect(): Rect? {
        return layoutData.occupiedScreenPartInTmp(pageLayoutManager.sceneRect)?.let { Rect(it) }
    }

    fun visibleRect(): Rect? {
        return layoutData.visibleOnScreenPart(pageLayoutManager.sceneRect)
    }

    internal suspend fun renderVisible(): Deferred<PageView?>? {
        return layoutData.visibleOnScreenPart(pageLayoutManager.sceneRect)?.let {
            return coroutineScope {
                async (Dispatchers.Main + handler) {
                    render(it, true)?.await()
                }
            }
        } ?: run { log("Non visible $pageNum"); null }

    }

    internal suspend fun renderInvisible(rect: Rect): Deferred<PageView?>? {
        //TODO yield
        return render(rect, false)
    }

    internal suspend fun render(rect: Rect, fromUI: Boolean): Deferred<PageView>? {
        val layoutStrategy = controller.layoutStrategy
        if (!(layoutStrategy.viewWidth > 0 &&  layoutStrategy.viewHeight > 0)) return null

        if (state != PageState.SIZE_AND_BITMAP_CREATED) {
            pageInfo.await()
        }
        tempRegion.set(nonRenderedRegion)
        if (true || tempRegion.op(rect, nonRenderedRegion, Region.Op.INTERSECT)) {
            //val bound = tempRegion.bounds
            val bound = Rect(rect)
            return coroutineScope {
                async(controller.context + pageJobs + handler) {
                    timing("Rendering $pageNum page in rendering engine: $bound") {
                        bitmap!!.render(bound, layoutInfo, page)
                    }
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            if (kotlin.coroutines.coroutineContext.isActive) {
                                nonRenderedRegion.op(bound, Region.Op.DIFFERENCE)
                                renderedRegion.union(bound)
                                log("PageView.render invalidate: $pageNum $layoutData ${scene != null}")
                                scene?.invalidate()
                                if (fromUI) {
                                    precache()
                                }
                            }
                        }
                    } else {
                        log("PageView.render: canceled")
                    }
                    this@PageView
                }
            }
        } else {
            if (state == PageState.SIZE_AND_BITMAP_CREATED) {
                log("Already rendered $state $document $pageNum: $rect")
                scene?.invalidate()
                val completableDeferred = CompletableDeferred<PageView>(coroutineContext.job)
                completableDeferred.complete(this@PageView)
                if (fromUI) {
                    precache()
                }
                return completableDeferred
            } else {
                log("Skipped $state $document $pageNum")
            }
        }
        return null
    }

    private val drawTmp  = Rect()
    private val drawSceneRect  = RectF()

    private fun draw(canvas: Canvas, bitmap: FlexibleBitmap, defaultPaint: Paint, scene: OrionDrawScene) {
        canvas.save()
        drawTmp.set(wholePageRect)
        drawTmp.offset(layoutData.position.x.toInt(), layoutData.position.y.toInt())
        if (drawTmp.intersect(pageLayoutManager.sceneRect)) {
            drawSceneRect.set(drawTmp)
            drawTmp.offset(-layoutData.position.x.toInt(), -layoutData.position.y.toInt())
            log("PageView.draw $pageNum: page=$drawTmp onScreen=$drawSceneRect")
            bitmap.draw(canvas, drawTmp, drawSceneRect, defaultPaint, scene.borderPaint!!)
        } else {
            log("PageView.draw: skipped $drawTmp $drawSceneRect")
        }
        drawBorder(canvas, scene)

        canvas.restore()
    }

    private fun drawBorder(
        canvas: Canvas,
        scene: OrionDrawScene,
    ) {
        canvas.drawRect(
            layoutData.globalRectInTmp(),
            scene.borderPaint!!
        )
    }

    fun invalidateAndUpdate() {
        invalidateAndMoveToStub()
        reinit()
    }

    fun invalidateAndMoveToStub() {
        marker++
        state = PageState.STUB
        pageJobs.cancelChildren()
    }
}