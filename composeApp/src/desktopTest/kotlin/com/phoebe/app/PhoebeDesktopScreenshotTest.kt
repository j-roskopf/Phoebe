package com.phoebe.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.phoebe.app.feature.radio.LocalRadioStationRemoteArtworkEnabled
import com.phoebe.app.ui.PhoebeDesignSystem
import com.phoebe.app.ui.PhoebeScreenshotApp
import com.phoebe.app.ui.PhoebeScreenshotScenario
import com.phoebe.app.ui.PhoebeTintOption
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

class PhoebeDesktopScreenshotTest {

    private val appearanceDesigns = listOf(
        PhoebeDesignSystem.Porcelain,
        PhoebeDesignSystem.Nocturne,
        PhoebeDesignSystem.Brutalist,
        PhoebeDesignSystem.Minimalist,
    )

    private val designScenarios = listOf(
        PhoebeScreenshotScenario.Home,
        PhoebeScreenshotScenario.Library,
        PhoebeScreenshotScenario.Album,
        PhoebeScreenshotScenario.Player,
        PhoebeScreenshotScenario.Settings,
    )

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopCoreFlowsDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        listOf(
            PhoebeScreenshotScenario.Home,
            PhoebeScreenshotScenario.HomePlayedRows,
            PhoebeScreenshotScenario.FavoritePlaylists,
            PhoebeScreenshotScenario.FavoriteArtists,
            PhoebeScreenshotScenario.FavoriteAlbums,
            PhoebeScreenshotScenario.Library,
            PhoebeScreenshotScenario.Playlist,
            PhoebeScreenshotScenario.Artist,
            PhoebeScreenshotScenario.ArtistRadio,
            PhoebeScreenshotScenario.Album,
            PhoebeScreenshotScenario.Search,
            PhoebeScreenshotScenario.Player,
            PhoebeScreenshotScenario.PlayerVisualizer,
            PhoebeScreenshotScenario.Settings,
            PhoebeScreenshotScenario.SignIn,
        ).forEach { scenario ->
            setContent {
                Box(Modifier.size(1365.dp, 900.dp)) {
                    PhoebeScreenshotApp(scenario = scenario)
                }
            }
            waitForIdle()
            onRoot().captureRoboImage(
                filePath = "src/screenshotTest/roborazzi/desktop-${scenario.name.lowercase()}-dark.png",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopDetailOldArtworkLayoutDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        listOf(
            PhoebeScreenshotScenario.ArtistOldLayout to "artist",
            PhoebeScreenshotScenario.AlbumOldLayout to "album",
        ).forEach { (scenario, slug) ->
            setContent {
                Box(Modifier.size(1365.dp, 900.dp)) {
                    PhoebeScreenshotApp(scenario = scenario)
                }
            }
            waitForIdle()
            onRoot().captureRoboImage(
                filePath = "src/screenshotTest/roborazzi/desktop-$slug-old-artwork-layout-dark.png",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopArtistEventsDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        listOf(
            PhoebeScreenshotScenario.ArtistWithEvents to "artist-events-link",
            PhoebeScreenshotScenario.ArtistEvents to "artist-events",
        ).forEach { (scenario, slug) ->
            setContent {
                Box(Modifier.size(1365.dp, 900.dp)) {
                    PhoebeScreenshotApp(scenario = scenario)
                }
            }
            waitForIdle()
            onRoot().captureRoboImage(
                filePath = "src/screenshotTest/roborazzi/desktop-$slug-dark.png",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopRadioDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        setContent {
            Box(Modifier.size(1365.dp, 900.dp)) {
                CompositionLocalProvider(LocalRadioStationRemoteArtworkEnabled provides false) {
                    PhoebeScreenshotApp(scenario = PhoebeScreenshotScenario.Radio)
                }
            }
        }
        waitForIdle()
        onRoot().captureRoboImage(
            filePath = "src/screenshotTest/roborazzi/desktop-radio-dark.png",
        )
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopLibraryScrollbarDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        setContent {
            Box(Modifier.size(1365.dp, 900.dp)) {
                PhoebeScreenshotApp(scenario = PhoebeScreenshotScenario.LibraryScrollbar)
            }
        }
        waitForIdle()
        onRoot().captureRoboImage(
            filePath = "src/screenshotTest/roborazzi/desktop-library-scrollbar-dark.png",
        )
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopRepresentativeFlowsLight() = runDesktopComposeUiTest(width = 1365, height = 900) {
        listOf(
            PhoebeScreenshotScenario.Home,
            PhoebeScreenshotScenario.Library,
            PhoebeScreenshotScenario.Search,
            PhoebeScreenshotScenario.Player,
        ).forEach { scenario ->
            setContent {
                Box(Modifier.size(1365.dp, 900.dp)) {
                    PhoebeScreenshotApp(
                        scenario = scenario,
                        useLightAppearance = true,
                    )
                }
            }
            waitForIdle()
            onRoot().captureRoboImage(
                filePath = "src/screenshotTest/roborazzi/desktop-${scenario.name.lowercase()}-light.png",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopVisualizerPresetsDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        listOf(
            PhoebeScreenshotScenario.PlayerVisualizerAlchemy to "alchemy",
            PhoebeScreenshotScenario.PlayerVisualizerBattery to "battery",
            PhoebeScreenshotScenario.PlayerVisualizerBarsAndWaves to "bars-and-waves",
            PhoebeScreenshotScenario.PlayerVisualizerBlazingColors to "blazing-colors",
            PhoebeScreenshotScenario.PlayerVisualizerPlenoptic to "plenoptic",
            PhoebeScreenshotScenario.PlayerVisualizerVortexSpectrum to "vortex-spectrum",
            PhoebeScreenshotScenario.PlayerVisualizerClassicEQ to "classic-eq",
            PhoebeScreenshotScenario.PlayerVisualizerHaloSpectrum to "halo-spectrum",
            PhoebeScreenshotScenario.PlayerVisualizerWireframeSpectrum3D to "wireframe-spectrum-3d",
        ).forEach { (scenario, slug) ->
            setContent {
                Box(Modifier.size(1365.dp, 900.dp)) {
                    PhoebeScreenshotApp(scenario = scenario)
                }
            }
            waitForIdle()
            onRoot().captureRoboImage(
                filePath = "src/screenshotTest/roborazzi/desktop-player-visualizer-$slug-dark.png",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopVisualizerTvFrameDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        setContent {
            Box(Modifier.size(1365.dp, 900.dp)) {
                PhoebeScreenshotApp(scenario = PhoebeScreenshotScenario.PlayerVisualizerTvFrame)
            }
        }
        waitForIdle()
        onRoot().captureRoboImage(
            filePath = "src/screenshotTest/roborazzi/desktop-player-visualizer-tv-frame-dark.png",
        )
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopTintedBackgroundsDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        listOf(
            PhoebeScreenshotScenario.Home,
            PhoebeScreenshotScenario.Library,
            PhoebeScreenshotScenario.Search,
        ).forEach { scenario ->
            setContent {
                Box(Modifier.size(1365.dp, 900.dp)) {
                    PhoebeScreenshotApp(
                        scenario = scenario,
                        tintId = PhoebeTintOption.fromId("red").id,
                    )
                }
            }
            waitForIdle()
            onRoot().captureRoboImage(
                filePath = "src/screenshotTest/roborazzi/desktop-${scenario.name.lowercase()}-red-tint-dark.png",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopTintSettings() = runDesktopComposeUiTest(width = 1365, height = 900) {
        listOf(false, true).forEach { useLightAppearance ->
            setContent {
                Box(Modifier.size(1365.dp, 900.dp)) {
                    PhoebeScreenshotApp(
                        scenario = PhoebeScreenshotScenario.Settings,
                        useLightAppearance = useLightAppearance,
                        tintId = PhoebeTintOption.fromId("blue").id,
                    )
                }
            }
            waitForIdle()
            onRoot().captureRoboImage(
                filePath = "src/screenshotTest/roborazzi/desktop-settings-blue-tint-${if (useLightAppearance) "light" else "dark"}.png",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopDesignSystemsRepresentativeFlows() = runDesktopComposeUiTest(width = 1365, height = 900) {
        appearanceDesigns.forEach { design ->
            listOf(false, true).forEach { useLightAppearance ->
                designScenarios.forEach { scenario ->
                    setContent {
                        Box(Modifier.size(1365.dp, 900.dp)) {
                            PhoebeScreenshotApp(
                                scenario = scenario,
                                useLightAppearance = useLightAppearance,
                                designId = design.id,
                            )
                        }
                    }
                    waitForIdle()
                    onRoot().captureRoboImage(
                        filePath = "src/screenshotTest/roborazzi/desktop-${design.id}-${scenario.name.lowercase()}-${if (useLightAppearance) "light" else "dark"}.png",
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopBrutalistDenseListDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        setContent {
            Box(Modifier.size(1365.dp, 900.dp)) {
                PhoebeScreenshotApp(
                    scenario = PhoebeScreenshotScenario.LibraryScrollbar,
                    designId = PhoebeDesignSystem.Brutalist.id,
                )
            }
        }
        waitForIdle()
        onRoot().captureRoboImage(
            filePath = "src/screenshotTest/roborazzi/desktop-brutalist-library-scrollbar-dark.png",
        )
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun desktopNocturnePlayerQueueDark() = runDesktopComposeUiTest(width = 1365, height = 900) {
        setContent {
            Box(Modifier.size(1365.dp, 900.dp)) {
                PhoebeScreenshotApp(
                    scenario = PhoebeScreenshotScenario.PlayerUpNextExpanded,
                    designId = PhoebeDesignSystem.Nocturne.id,
                    forceShowQueue = true,
                )
            }
        }
        waitForIdle()
        onRoot().captureRoboImage(
            filePath = "src/screenshotTest/roborazzi/desktop-nocturne-player-queue-dark.png",
        )
    }
}
