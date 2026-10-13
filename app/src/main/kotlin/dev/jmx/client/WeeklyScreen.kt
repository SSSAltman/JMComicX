package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import dev.jmx.client.core.api.WeekCategory
import dev.jmx.client.core.api.WeekType
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** `/week` 返回的两组筛选项：栏目（哪一期）与类型（榜单种类）。 */
internal data class WeeklyFilters(
    val categories: List<WeekCategory>,
    val types: List<WeekType>,
)

@Composable
internal fun WeeklyScreen(
    innerPadding: PaddingValues,
    repository: RankingRepository,
    liftedAlbumId: String?,
    onAlbumSelected: (HomeAlbum, Rect) -> Unit,
) {
    var filters by remember(repository) { mutableStateOf<WeeklyFilters?>(null) }
    var filtersError by remember(repository) { mutableStateOf<String?>(null) }
    var filtersRetry by remember { mutableIntStateOf(0) }
    // 选中项记 id 而不是下标：/week 重新返回时顺序可能变，只有 id 能稳定定位同一期/同一类型。
    var categoryId by remember { mutableStateOf<String?>(null) }
    var typeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, filtersRetry) {
        filters = null
        filtersError = null
        when (val result = repository.loadWeekFilters()) {
            is JmxResult.Success -> {
                val loaded = result.value
                val knownCategoryIds = loaded.categories.map { it.id }
                val knownTypeIds = loaded.types.map { it.id }
                filters = loaded
                categoryId = categoryId?.takeIf { it in knownCategoryIds } ?: knownCategoryIds.firstOrNull()
                typeId = typeId?.takeIf { it in knownTypeIds } ?: knownTypeIds.firstOrNull()
            }
            is JmxResult.Failure -> filtersError = result.error.toUiMessage()
        }
    }

    val selectedCategoryId = categoryId
    val selectedTypeId = typeId
    // 换一期/换一个类型都等于换一份列表，旧的分页状态不能续用。
    var state by remember(selectedCategoryId, selectedTypeId) {
        mutableStateOf<RankingListState>(RankingListState.Loading)
    }
    var listRetry by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    var loadJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(selectedCategoryId, selectedTypeId, listRetry) {
        onDispose { loadJob?.cancel() }
    }
    LaunchedEffect(selectedCategoryId, selectedTypeId, repository, listRetry) {
        val category = selectedCategoryId ?: return@LaunchedEffect
        val type = selectedTypeId ?: return@LaunchedEffect
        // 重试时 remember 的键没变，状态不会自己回到 Loading，这里显式覆盖掉上一条错误。
        state = RankingListState.Loading
        state = when (val result = repository.loadWeekPage(category, type, page = 1)) {
            is JmxResult.Success -> rankingContentAfterPage(null, result.value, loadedPage = 1)
            is JmxResult.Failure -> RankingListState.Error(result.error.toUiMessage())
        }
    }

    fun loadMore() {
        val content = state as? RankingListState.Content ?: return
        val category = selectedCategoryId ?: return
        val type = selectedTypeId ?: return
        if (content.isLoadingMore || content.endReached) return
        val requestedPage = content.nextPage
        state = content.copy(isLoadingMore = true, loadMoreError = null)
        loadJob = coroutineScope.launch {
            when (val result = repository.loadWeekPage(category, type, requestedPage)) {
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

    val loadedFilters = filters
    val error = filtersError
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MiuixTheme.colorScheme.surface),
    ) {
        when {
            error != null -> RankingMessage(
                message = error,
                modifier = Modifier.weight(1f),
                action = "重试",
                onAction = { filtersRetry++ },
            )
            loadedFilters == null -> AccountPageLoading(modifier = Modifier.weight(1f))
            loadedFilters.categories.isEmpty() || loadedFilters.types.isEmpty() -> RankingMessage(
                message = "服务端没有返回每周必看的筛选项。",
                modifier = Modifier.weight(1f),
            )
            else -> {
                RankingChipRow(
                    chips = loadedFilters.categories.toCategoryChips(),
                    selectedId = selectedCategoryId.orEmpty(),
                    onSelect = { categoryId = it },
                )
                RankingChipRow(
                    chips = loadedFilters.types.toTypeChips(),
                    selectedId = selectedTypeId.orEmpty(),
                    onSelect = { typeId = it },
                )
                Box(modifier = Modifier.weight(1f)) {
                    when (val current = state) {
                        RankingListState.Loading -> AccountPageLoading()
                        is RankingListState.Error -> RankingMessage(
                            message = current.message,
                            action = "重试",
                            onAction = { listRetry++ },
                        )
                        is RankingListState.Content -> if (current.albums.isEmpty()) {
                            RankingMessage(message = "这一期每周必看暂时没有漫画。")
                        } else {
                            RankingAlbumGrid(
                                content = current,
                                listDescription = "每周必看漫画列表",
                                liftedAlbumId = liftedAlbumId,
                                onAlbumSelected = onAlbumSelected,
                                onLoadMore = ::loadMore,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 标题缺失时退回可辨识的编号，避免胶囊变成空白。 */
private fun List<WeekCategory>.toCategoryChips(): List<RankingChip> = map { category ->
    RankingChip(
        id = category.id,
        label = category.title?.decodeHtml()?.trim().orEmpty()
            .ifBlank { "栏目 ${category.id}" },
    )
}

private fun List<WeekType>.toTypeChips(): List<RankingChip> = map { type ->
    RankingChip(
        id = type.id,
        label = type.title?.decodeHtml()?.trim().orEmpty()
            .ifBlank { "类型 ${type.id}" },
    )
}
