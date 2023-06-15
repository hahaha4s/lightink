package cn.lightink.reader.controller

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.lightink.reader.BOOK_PATH
import cn.lightink.reader.ktx.encode
import cn.lightink.reader.ktx.only
import cn.lightink.reader.ktx.toJson
import cn.lightink.reader.model.Book
import cn.lightink.reader.model.Bookshelf
import cn.lightink.reader.model.MPMetadata
import cn.lightink.reader.module.*
import com.scwang.smartrefresh.layout.constant.RefreshState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

class MainController : ViewModel() {

    val bookshelfCheckUpdateLiveData = MutableLiveData<RefreshState>()
    val bookshelfLive = MutableLiveData(getTheLastBookshelf())

    private var checkUpdateJob: Deferred<Int>? = null

    /**
     * 切换书架
     */
    fun changedBookshelf(bookshelf: Bookshelf? = getTheLastBookshelf()) {
        bookshelfLive.postValue(bookshelf)
    }

    /**
     * 查询书架列表
     */
    fun queryBookshelves() = Room.bookshelf().getAll()

    /**
     * 查询指定书架中的图书
     * @param bookshelf 指定书架
     */
    fun queryBooksByBookshelf(bookshelf: Bookshelf) = if (bookshelf.sort == 1) {
        Room.book().getAllSortByDrag(bookshelf.id)
    } else {
        Room.book().getAllSortByTime(bookshelf.id)
    }

    /**
     * 移动图书
     */
    fun moveBooks(books: List<Book>, bookshelf: Bookshelf) {
        if (books.isEmpty()) return
        books.forEach { book -> book.bookshelf = bookshelf.id }
        Room.book().update(*books.toTypedArray())
    }

    /**
     * 删除图书
     */
    fun deleteBooks(books: List<Book>, withResource: Boolean) {
        if (books.isEmpty()) return
        Room.book().delete(*books.toTypedArray())
        books.map { File(BOOK_PATH, it.objectId) }.forEach { it.deleteRecursively() }
        books.forEach { Room.bookRecord().remove(it.objectId) }
    }

    /**
     * 删除书架
     */
    fun deleteBookshelf(bookshelf: Bookshelf) {
        Room.bookshelf().remove(bookshelf)
        if (Preferences.get(Preferences.Key.BOOKSHELF, 1L) == bookshelf.id) {
            val defaultBookshelf = Room.bookshelf().getFirst()
            Preferences.put(Preferences.Key.BOOKSHELF, defaultBookshelf.id)
            changedBookshelf(defaultBookshelf)
        }
        deleteBooks(Room.book().getAll(bookshelf.id), withResource = true)
    }

    /**
     * 获取图书更新方式
     */
    fun getBookCheckUpdateType() = Preferences.get(Preferences.Key.BOOK_CHECK_UPDATE_TYPE, 60)

    /**
     * 检查更新
     */
    fun checkBooksUpdate() = viewModelScope.launch {
        //当前有检查任务则不再重复执行
        if (checkUpdateJob?.isActive == true) return@launch
        if (Room.book().hasNeedCheckUpdate()) {
            checkUpdateJob = async(Dispatchers.IO) {
                val books = Room.book().getAllNeedCheckUpdate().filter { it.hasBookSource() }
                books.forEach { book ->
                    launch { book.getBookSource()?.run { checkUpdate(book) } }
                }
                return@async books.size
            }
            bookshelfCheckUpdateLiveData.postValue(if (checkUpdateJob!!.await() == 0) RefreshState.PullDownCanceled else RefreshState.PullDownCanceled)
        } else {
            bookshelfCheckUpdateLiveData.postValue(RefreshState.PullDownCanceled)
        }
    }

    /**
     * 读取上次浏览的书架
     */
    private fun getTheLastBookshelf() = Room.bookshelf().get(Preferences.get(Preferences.Key.BOOKSHELF, Room.bookshelf().getFirst().id))

    /**
     * 清理不存在记录但文件残留的图书
     */
    fun autoClearInvalidBook(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val books = Room.book().getObjectIdAll()
            BOOK_PATH.listFiles()?.filter { it.isFile || !books.contains(it.name) }?.forEach { it.deleteRecursively() }
            if (File(context.cacheDir, "WebDAV").exists()) File(context.cacheDir, "WebDAV").deleteRecursively()
        }
    }

    fun addLocalBookToBookshelf(context: Context, uri: Uri, bookshelf: Bookshelf? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i("tag", uri.path ?: "uri.path is null")
            // new book
            var fileName: String? = null
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    fileName = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
            if (fileName != null) {
                Log.i("tag", fileName!!)

                // val file = File(book!!.path, "$MP_FOLDER_TEXTS/${chapter.encodeHref}.md")
                val bookMetadata = MPMetadata(fileName!!, LOCAL_BOOK_AUTHOR, uri.path!!, state = BOOK_STATE_END)
                //构造图书对象
                val book = Book(bookMetadata, bookshelf?.id ?: -1L)
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                val regex = Regex("第[0-9一二三四五六七八九十百千]+[章节卷集部篇回话].*\\r\\n")
                val matches = regex.findAll(content!!)
                val chaptersCount = matches.count()
                if (chaptersCount > 0) {

                    //生成输出目录
                    val output = File(BOOK_PATH, bookMetadata.objectId).only()
                    //生成图片文件夹
                    File(output, MP_FOLDER_IMAGES).mkdirs()
                    //生成章节文件夹
                    val chapterDirectory = File(output, MP_FOLDER_TEXTS).apply { mkdirs() }

                    val chapters = MutableList(chaptersCount) { LocalChapter() }


                    matches.forEachIndexed { index, matchResult ->
                        when(index) {
                            0 -> {
                                val firstChapter = chapters[0]
                                firstChapter.name = matchResult.value.trim()
                                firstChapter.start = matchResult.range.last
                            }
                            chaptersCount - 1 -> {
                                val previousChapter = chapters[index - 1]
                                previousChapter.end = matchResult.range.first
                                previousChapter.content = content.substring(previousChapter.start, previousChapter.end)

                                val currentChapter = chapters[index]
                                currentChapter.name = matchResult.value.trim()
                                currentChapter.start = matchResult.range.last

                                currentChapter.content = content.substring(matchResult.range.last)
                            }
                            else -> {
                                val previousChapter = chapters[index - 1]
                                previousChapter.end = matchResult.range.first
                                previousChapter.content = content.substring(previousChapter.start, previousChapter.end)

                                val currentChapter = chapters[index]
                                currentChapter.name = matchResult.value.trim()
                                currentChapter.start = matchResult.range.last

                            }
                        }

                    }
                    for (chapter in chapters) {
                        val chapterPath = "${chapter.name.encode()}.md"
                        File(chapterDirectory, chapterPath).apply { createNewFile() }.writeText(chapter.content)
                    }

                    publish(book, bookMetadata, chapters, output, bookshelf)
                }
            }
        }
    }


    /**
     * 将网络图书转为本地图书
     * @param bookshelf     指定书架 无数据就是预览模式
     */
    fun publish(book: Book, bookMetadata: MPMetadata, chapters: List<LocalChapter>, output: File, bookshelf: Bookshelf? = null) {
        try {

            //生成目录
            val catalog = File(output, MP_FILENAME_CATALOG).apply { createNewFile() }
            chapters.forEach { chapter ->
//                if (chapter.useLevel) catalog.appendText(MP_CATALOG_INDENTATION)
                catalog.appendText("* [${chapter.name}](${chapter.name})$MP_ENTER")
            }
            //存储元数据
            File(output, MP_FILENAME_METADATA).writeText(bookMetadata.toJson())
            //存储书源
//            bookSource?.run { File(output, MP_FILENAME_BOOK_SOURCE).writeText(toJson()) }
            //构造图书对象
            book.catalog = chapters.size
            book.lastChapter = chapters.last().name
            /*if (URLUtil.isNetworkUrl(baseInfo?.cover ?: metadata.cover)) withContext(Dispatchers.IO) {
                if (File(book.cover).parentFile?.exists() == false) File(book.cover).parentFile?.mkdirs()
                try {
                    Http.download(baseInfo?.cover ?: metadata.cover).data?.run { File(book.cover).writeBytes(this) }
                } catch (e: Exception) {
                    //防止超时或其他网络错误
                }
            }*/
            Room.book().insert(book)
            Room.book().getAll()
//            Room.bookSource().get(if (bookSource?.url?.startsWith("http") == true) Uri.parse(bookSource?.url)?.host.orEmpty() else bookSource?.url.orEmpty())?.run { Room.bookSource().update(this.apply { frequency += 1 }) }
        } catch (e: Exception) {
            //防止写入文件时因缓存删除报错
            e.printStackTrace()
        }
    }
}

data class LocalChapter(
    var name: String = "",
    var start: Int = 0,
    var end: Int = 0,
    var content: String = ""
)