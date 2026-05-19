package com.phoebe.app.ui

import com.phoebe.app.domain.CatalogPageInfo
import com.phoebe.app.domain.CatalogSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryPageTest {
    @Test
    fun disabledPaginationReturnsEveryItem() {
        val items = (1..250).toList()

        val page = libraryPage(items, enabled = false, pageIndex = 3)

        assertEquals(items, page.items)
        assertEquals(1, page.pageCount)
        assertEquals(250, page.totalCount)
    }

    @Test
    fun enabledPaginationReturnsRequestedWindow() {
        val items = (1..250).toList()

        val page = libraryPage(items, enabled = true, pageIndex = 1)

        assertEquals((101..200).toList(), page.items)
        assertEquals(3, page.pageCount)
        assertEquals(250, page.totalCount)
    }

    @Test
    fun enabledPaginationUsesRemotePageSize() {
        val items = (1..125).toList()
        val pageSize = remoteLibraryPageSize(
            CatalogSnapshot(remotePageInfo = CatalogPageInfo(pageSize = 50)),
            enabled = true,
        )

        val page = libraryPage(items, enabled = true, pageIndex = 1, totalCountOverride = 125, pageSize = pageSize)

        assertEquals((51..100).toList(), page.items)
        assertEquals(3, page.pageCount)
        assertEquals(50, page.pageSize)
        assertEquals(51, page.firstItemNumber)
        assertEquals(100, page.lastItemNumber)
    }
}
