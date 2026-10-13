package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.api.AlbumPage
import dev.jmx.client.core.api.CategoryFilter
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.effect.BlurTabRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 榜单周期。
 *
 * 只带出 `t` 参数：`o` 由 [dev.jmx.client.core.api.CategoryFilter.mobileOrderParam] 从
 * (order, time) 派生，日/周/月得到的正是 `mv_t`/`mv_w`/`mv_m`（即
 * [JmxMagicConstants.ORDER_DAY_RANKING] 等常量），总榜因为 [JmxMagicConstants.TIME_ALL]
 * 而不加后缀。声明顺序就是标签顺序。
 */
internal enum class RankingPeriod(val time: String, val label: String) {
    DAY(JmxMagicConstants.TIME_TODAY, "日榜"),
    WEEK(JmxMagicConstants.TIME_WEEK, "周榜"),
    MONTH(JmxMagicConstants.TIME_MONTH, "月榜"),
    ALL(JmxMagicConstants.TIME_ALL, "总榜"),
}

/** 筛选胶囊：`id` 是接口参数值，`label` 只做展示。 */
internal data class RankingChip(val id: String, val label: String)

/**
 * 榜单的分类筛选行。取值全部来自 [JmxMagicConstants.CATEGORY_*]，标签是站方对这些分类的
 * 习惯叫法；`0` 是"全部"，服务端把它当"不加分类限制"。
 */
internal val RANKING_CATEGORY_CHIPS = listOf(
    RankingChip(JmxMagicConstants.CATEGORY_ALL, "全部"),
    RankingChip(JmxMagicConstants.CATEGORY_DOUJIN, "同人"),
    RankingChip(JmxMagicConstants.CATEGORY_SINGLE, "单行本"),
    RankingChip(JmxMagicConstants.CATEGORY_SHORT, "短篇"),
    RankingChip(JmxMagicConstants.CATEGORY_ANOTHER, "其他"),
    RankingChip(JmxMagicConstants.CATEGORY_HANMAN, "汉化"),
    RankingChip(JmxMagicConstants.CATEGORY_MEIMAN, "美漫"),
    RankingChip(JmxMagicConstants.CATEGORY_DOUJIN_COSPLAY, "同人Cosplay"),
    RankingChip(JmxMagicConstants.CATEGORY_3D, "3D"),
    RankingChip(JmxMagicConstants.CATEGORY_ENGLISH_SITE, "非中文"),
)

@Composable
internal fun RankingScreen(
    innerPadding: PaddingValues,
    repository: RankingRepository,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
) {
    var period by rememberSaveable { mutableStateOf(RankingPeriod.DAY) }
    var category by rememberSaveable { mutableStateOf(JmxMagicConstants.CATEGORY_ALL) }
    // 翻页状态跟着 (周期, 分类) 重建：切榜相当于换一份列表，续上旧列表只会串页。
    var state by remember(period, category) { mutableStateOf<RankingListState>(RankingListState.Loading) }
    var retryKey by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    var loadJob by remember { mutableStateOf<Job?>(null) }
    val categoryLabel = remember(category) {
        RANKING_CATEGORY_CHIPS.firstOrNull { it.id == category }?.label.orEmpty()
    }

    DisposableEffect(period, category, retryKey) {
        onDispose { loadJob?.cancel() }
    }
    LaunchedEffect(period, category, repository, retryKey) {
        // 重试时 remember 的键没变，状态不会自己回到 Loading，这里显式覆盖掉上一条错误。
        state = RankingListState.Loading
        state = when (val result = repository.loadRankingPage(period, category, page = 1)) {
            is JmxResult.Success -> rankingContentAfterPage(null, result.value, loadedPage = 1)
            is JmxResult.Failure -> RankingListState.Error(result.error.toUiMessage())
        }
    }

    fun loadMore() {
        val content = state as? RankingListState.Content ?: return
        if (content.isLoadingMore || content.endReached) return
        val requestedPage = content.nextPage
        state = content.copy(isLoadingMore = true, loadMoreError = null)
        loadJob = coroutineScope.launch {
            when (val result = repository.loadRankingPage(period, category, requestedPage)) {
                is JmxResult.Success -> {
                    val current = state as? RankingListState.Content ?: return@launch
                    state = rankingContentAfterPage(current, result.value, requestedPage)
                }
                is JmxResult.Failure -> {
                    val current = state as? RankingListState.Content ?: return@launch
                    state = current.copy(isLoadingMore = false, loadMoreError = result.error.toUiMessage())
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MiuixTheme.colorScheme.surface),
    ) {
        BlurTabRow(
            tabs = RankingPeriod.entries.map { it.label },
            selectedIndex = period.ordinal,
            onTabSelected = { index -> RankingPeriod.entries.getOrNull(index)?.let { period = it } },
        )
        RankingChipRow(
            chips = RANKING_CATEGORY_CHIPS,
            selectedId = category,
            onSelect = { category = it },
        )
        Box(modifier = Modifier.weight(1f)) {
            when (val current = state) {
                RankingListState.Loading -> AccountPageLoading()
                is RankingListState.Error -> RankingMessage(
                    message = current.message,
                    action = "重试",
                    onAction = { retryKey++ },
                )
                is RankingListState.Content -> if (current.albums.isEmpty()) {
                    RankingMessage(message = "${period.label}暂时没有漫画。")
                } else {
                    RankingAlbumGrid(
                        content = current,
                        listDescription = "${period.label}·${categoryLabel}漫画列表",
                        liftedAlbumId = liftedAlbumId,
                        onAlbumSelected = onAlbumSelected,
                        onLoadMore = ::loadMore,
                    )
                }
            }
        }
    }
}

/**
 * 接一页结果。
 *
 * 榜单各页之间会有并列位次导致的重复条目，去重后才能判断是否真的翻到了头；
 * `total` 缺失时沿用上一页的值，不能因为这一页没带总数就假装没有下一页。
 */
internal fun rankingContentAfterPage(
    previous: RankingListState.Content?,
    page: RankingAlbumPage,
    loadedPage: Int,
): RankingListState.Content {
    val existing = previous?.albums.orEmpty()
    val existingIds = existing.mapTo(hashSetOf()) { it.id }
    val incoming = page.albums.filter { it.id !in existingIds }
    val merged = existing + incoming
    val total = page.total ?: previous?.total
    return RankingListState.Content(
        albums = merged,
        total = total,
        nextPage = loadedPage + 1,
        endReached = incoming.isEmpty() || (total != null && merged.size >= total),
    )
}

internal sealed interface RankingListState {
    data object Loading : RankingListState
    data class Error(val message: String) : RankingListState
    data class Content(
        val albums: List<HomeAlbum>,
        val total: Int?,
        val nextPage: Int,
        val isLoadingMore: Boolean = false,
        val loadMoreError: String? = null,
        val endReached: Boolean = false,
    ) : RankingListState
}

/** 一页漫画（已映射成可直接渲染的 [HomeAlbum]）加上服务端总数。 */
internal data class RankingAlbumPage(
    val albums: List<HomeAlbum>,
    val total: Int?,
)

@Composable
internal fun RankingChipRow(
    chips: List<RankingChip>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    if (chips.isEmpty()) return
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val selected = chip.id == selectedId
            Surface(
                shape = shape,
                color = if (selected) {
                    MiuixTheme.colorScheme.primaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .clickable { onSelect(chip.id) }
                        // 选中态只有配色差异，读屏时听不出来，所以并进朗读文本。
                        .semantics { contentDescription = chip.label + if (selected) "，当前筛选" else "" }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = chip.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (selected) {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RankingAlbumGrid(
    content: RankingListState.Content,
    listDescription: String,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val footerVisible by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { item -> item.key == RANKING_GRID_FOOTER }
        }
    }
    // 每页最多自动加载一次：footer 仍然可见时（平板上一页就装得下）靠 nextPage 变化重新上膛，
    // 加载失败则只留重试按钮，不再自动重试刷接口。
    var loadMoreArmed by remember(content.nextPage) { mutableStateOf(true) }
    LaunchedEffect(footerVisible) {
        if (!footerVisible) loadMoreArmed = true
    }
    LaunchedEffect(
        footerVisible,
        loadMoreArmed,
        content.isLoadingMore,
        content.loadMoreError,
        content.endReached,
        content.nextPage,
    ) {
        if (
            footerVisible &&
            loadMoreArmed &&
            !content.isLoadingMore &&
            content.loadMoreError == null &&
            !content.endReached
        ) {
            loadMoreArmed = false
            onLoadMore()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "$listDescription，共${content.albums.size}部" },
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items = content.albums, key = { it.id }) { album ->
            AlbumItem(
                album = album,
                coverLifted = album.id == liftedAlbumId,
                onSelected = onAlbumSelected,
            )
        }
        item(key = RANKING_GRID_FOOTER, span = { GridItemSpan(maxLineSpan) }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    content.isLoadingMore -> CircularProgressIndicator(size = 24.dp, strokeWidth = 3.dp)
                    content.loadMoreError != null -> TextButton(text = "加载失败，重试", onClick = onLoadMore)
                    content.endReached -> Text(
                        text = "已显示全部 ${content.albums.size} 部漫画",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RankingMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            if (action != null) TextButton(text = action, onClick = onAction)
        }
    }
}

/**
 * 榜单与每周必看的取数层。
 *
 * 出网之后的映射（`toRawMap` 会把嵌套 JSON 重新串成字符串、封面地址解析）整段放在 IO 线程，
 * 与首页、收藏页同一处理：留在 Composition 线程上就会在"内容即将出现"的那一刻卡住界面。
 */
internal class RankingRepository(
    private val homeRepository: HomeRepository,
) {
    private val core get() = homeRepository.core

    suspend fun loadRankingPage(
        period: RankingPeriod,
        category: String,
        page: Int,
    ): JmxResult<RankingAlbumPage> = requestPage {
        core.libraryApi.categoriesFilter(
            CategoryFilter(
                page = page,
                time = period.time,
                category = category,
                order = JmxMagicConstants.ORDER_BY_VIEW,
            )
        )
    }

    /** `/week` 只用来取筛选项本身，栏目与类型都必须来自接口，不在客户端硬编码。 */
    suspend fun loadWeekFilters(): JmxResult<WeeklyFilters> = withContext(Dispatchers.IO) {
        try {
            when (val result = core.libraryApi.week()) {
                is JmxResult.Failure -> result
                is JmxResult.Success -> JmxResult.Success(
                    WeeklyFilters(categories = result.value.categories, types = result.value.types)
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            JmxResult.Failure(JmxError.Unknown(error.message ?: "每周必看筛选出现未知异常。"))
        }
    }

    suspend fun loadWeekPage(
        categoryId: String,
        typeId: String,
        page: Int,
    ): JmxResult<RankingAlbumPage> = requestPage {
        core.libraryApi.weekFilter(page = page, categoryId = categoryId, typeId = typeId)
    }

    private suspend fun requestPage(
        request: suspend () -> JmxResult<AlbumPage>,
    ): JmxResult<RankingAlbumPage> = withContext(Dispatchers.IO) {
        try {
            when (val result = request()) {
                is JmxResult.Failure -> result
                is JmxResult.Success -> JmxResult.Success(
                    RankingAlbumPage(
                        albums = result.value.content
                            .filter { it.id.isNotBlank() }
                            .distinctBy { it.id }
                            .map { it.toHomeAlbum(homeRepository.currentImageHost) },
                        total = result.value.total,
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            JmxResult.Failure(JmxError.Unknown(error.message ?: "榜单加载出现未知异常。"))
        }
    }
}

private const val RANKING_GRID_FOOTER = "ranking-grid-footer"
