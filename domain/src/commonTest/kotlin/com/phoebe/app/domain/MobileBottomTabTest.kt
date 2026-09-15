package com.phoebe.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MobileBottomTabTest {
    @Test
    fun fallsBackToDefaultWhenFewerThanTwoValidTabsRemain() {
        assertEquals(
            MobileBottomTab.defaultOrder,
            listOf(MobileBottomTab.Radio, MobileBottomTab.Radio).normalizedMobileBottomTabs(),
        )
    }

    @Test
    fun preservesCustomVisibleOrder() {
        assertEquals(
            listOf(
                MobileBottomTab.Radio,
                MobileBottomTab.Home,
            ),
            listOf(MobileBottomTab.Radio, MobileBottomTab.Home).normalizedMobileBottomTabs(),
        )
    }

    @Test
    fun stripsChartsFromPersistedTabSets() {
        assertEquals(
            listOf(
                MobileBottomTab.Home,
                MobileBottomTab.Search,
                MobileBottomTab.Library,
                MobileBottomTab.Playlists,
                MobileBottomTab.Radio,
            ),
            listOf(
                MobileBottomTab.Home,
                MobileBottomTab.Search,
                MobileBottomTab.Library,
                MobileBottomTab.Charts,
                MobileBottomTab.Playlists,
                MobileBottomTab.Radio,
            ).normalizedMobileBottomTabs(),
        )
    }

    @Test
    fun doesNotForceExtraTabsOntoCustomSets() {
        assertEquals(
            listOf(
                MobileBottomTab.Home,
                MobileBottomTab.Library,
                MobileBottomTab.Radio,
            ),
            listOf(
                MobileBottomTab.Home,
                MobileBottomTab.Library,
                MobileBottomTab.Radio,
            ).normalizedMobileBottomTabs(),
        )
    }
}
