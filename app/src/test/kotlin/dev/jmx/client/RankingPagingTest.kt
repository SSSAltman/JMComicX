package dev.jmx.client

import dev.jmx.client.core.protocol.JmxMagicConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class RankingPagingTest {
    @Test
    fun periodsCoverEveryRankingWindowInTabOrder() {
        assertEquals(
            listOf(
                JmxMagicConstants.TIME_TODAY,
                JmxMagicConstants.TIME_WEEK,
                JmxMagicConstants.TIME_MONTH,
                JmxMagicConstants.TIME_ALL,
            ),
            RankingPeriod.entries.map { it.time },
        )
        assertEquals(listOf("日榜", "周榜", "月榜", "总榜"), RankingPeriod.entries.map { it.label })
    }

    @Test
    fun firstPageKeepsServerTotalAndStartsFromTheNextPage() {
        val content = rankingContentAfterPage(
            previous = null,
            page = RankingAlbumPage(listOf(album("1"), album("2")), total = 5),
            loadedPage = 1,
        )
        assertEquals(listOf(album("1"), album("2")), content.albums)
        assertEquals(5, content.total)
        assertEquals(2, content.nextPage)
        assertEquals(false, content.endReached)
    }

    @Test
    fun repeatedAlbumsAcrossPagesEndPaging() {
        val opened = rankingContentAfterPage(null, RankingAlbumPage(listOf(album("1"), album("2")), total = null), 1)
        val afterRepeat = rankingContentAfterPage(opened, RankingAlbumPage(listOf(album("2")), total = null), 2)
        assertEquals(listOf(album("1"), album("2")), afterRepeat.albums)
        assertEquals(true, afterRepeat.endReached)
    }

    @Test
    fun pagingEndsOnceLoadedCountReachesTotal() {
        val opened = rankingContentAfterPage(null, RankingAlbumPage(listOf(album("1")), total = 2), 1)
        val filled = rankingContentAfterPage(opened, RankingAlbumPage(listOf(album("2")), total = 2), 2)
        assertEquals(false, opened.endReached)
        assertEquals(true, filled.endReached)
    }

    @Test
    fun totalFromAnEarlierPageSurvivesPagesWithoutOne() {
        val opened = rankingContentAfterPage(null, RankingAlbumPage(listOf(album("1")), total = 30), 1)
        val followed = rankingContentAfterPage(opened, RankingAlbumPage(listOf(album("2")), total = null), 2)
        assertEquals(30, followed.total)
        assertEquals(false, followed.endReached)
    }

    @Test
    fun categoryChipsUseEveryServerCategoryConstant() {
        assertEquals(
            listOf(
                JmxMagicConstants.CATEGORY_ALL,
                JmxMagicConstants.CATEGORY_DOUJIN,
                JmxMagicConstants.CATEGORY_SINGLE,
                JmxMagicConstants.CATEGORY_SHORT,
                JmxMagicConstants.CATEGORY_ANOTHER,
                JmxMagicConstants.CATEGORY_HANMAN,
                JmxMagicConstants.CATEGORY_MEIMAN,
                JmxMagicConstants.CATEGORY_DOUJIN_COSPLAY,
                JmxMagicConstants.CATEGORY_3D,
                JmxMagicConstants.CATEGORY_ENGLISH_SITE,
            ),
            RANKING_CATEGORY_CHIPS.map { it.id },
        )
    }

    private fun album(id: String) = HomeAlbum(id, "作品$id", "作者", "https://img.test/$id", "https://img.test")
}
