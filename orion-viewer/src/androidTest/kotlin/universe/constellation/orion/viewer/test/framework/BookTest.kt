package universe.constellation.orion.viewer.test.framework

import android.content.Intent
import android.graphics.Point
import android.net.Uri
import org.junit.After
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import universe.constellation.orion.viewer.BuildConfig
import universe.constellation.orion.viewer.OrionViewerActivity
import java.io.File

@RunWith(Parameterized::class)
abstract class BookTest(path: String) : BaseTest() {

    constructor(bookDescription: BookDescription): this(bookDescription.path)

    private val documentDelegate = lazy {  openTestBook(path) }

    protected val document by documentDelegate
    @After
    fun close() {
        if (documentDelegate.isInitialized()) {
            document.destroy()
        }
    }
}

enum class BookDescription(
        val path: String,
        val pageCount: Int,
        val title: String?,
        val topLevelOutlineItems: Int,
        val allOutlineItems: Int = topLevelOutlineItems,
        val pageSize: Point = Point(0, 0)
) {
    SICP(BaseTest.SICP, 762, "", 15, 139, Point(662, 885)),
    ALICE(BaseTest.ALICE, 77, null, 0,  pageSize = Point(2481, 3508)),
    DJVU_SPEC(BaseTest.DJVU_SPEC, 71, null, 1, 100, Point(2539, 3295));

    fun toOpenIntent(): Intent {
        val path = extractFileFromTestData(path)
        return Intent(Intent.ACTION_VIEW).apply {
            setClassName(
                BuildConfig.APPLICATION_ID,
                OrionViewerActivity::class.qualifiedName!!
            )
            data = Uri.fromFile(path)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    fun asFile() = File(BaseTest.testFolder, path)

    companion object {
        private fun testEntries(): List<BookDescription> {
            if (MANUAL_DEBUG) {
                return listOf(BookDescription.entries[0])
            } else {
                return BookDescription.entries
            }
        }

        fun testData(): Iterable<Array<BookDescription>> {
            return testEntries().map { arrayOf(it) }
        }
    }
}