package com.phoebe.app

import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.phoebe.app.ui.PhoebeScreenshotApp
import com.phoebe.app.ui.PhoebeScreenshotScenario
import com.phoebe.app.ui.PhoebeTintOption
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotTestApplication::class)
class PhoebeAndroidPhoneScreenshotTest {
    @Test fun phoneHomeDark() = capturePhone("home", PhoebeScreenshotScenario.Home)
    @Test fun phoneHomeExpandedDark() = capturePhone("home-expanded", PhoebeScreenshotScenario.HomeExpanded)
    @Test fun phoneHomeAccordionsCollapsedDark() = capturePhone("home-accordions-collapsed", PhoebeScreenshotScenario.HomeAccordionsCollapsed)
    @Test fun phoneHomeAccordionsExpandedDark() = capturePhone("home-accordions-expanded", PhoebeScreenshotScenario.HomeAccordionsExpanded)
    @Test fun phoneHomePlayedRowsDark() = capturePhone("home-played-rows", PhoebeScreenshotScenario.HomePlayedRows)
    @Test fun phoneFavoritePlaylistsDark() = capturePhone("favorite-playlists", PhoebeScreenshotScenario.FavoritePlaylists)
    @Test fun phoneFavoriteArtistsDark() = capturePhone("favorite-artists", PhoebeScreenshotScenario.FavoriteArtists)
    @Test fun phoneFavoriteAlbumsDark() = capturePhone("favorite-albums", PhoebeScreenshotScenario.FavoriteAlbums)
    @Test fun phoneArtistRadioDark() = capturePhone("artist-radio", PhoebeScreenshotScenario.ArtistRadio)
    @Test fun phoneLibraryDark() = capturePhone("library", PhoebeScreenshotScenario.Library)
    @Test fun phoneLibraryScrollbarDark() = capturePhone("library-scrollbar", PhoebeScreenshotScenario.LibraryScrollbar)
    @Test fun phoneLibraryFiveColumnGridDark() = capturePhone("library-five-column-grid", PhoebeScreenshotScenario.LibraryFiveColumnGrid)
    @Test fun phoneRadioDark() = capturePhone("radio", PhoebeScreenshotScenario.Radio)
    @Test fun phonePlaylistDark() = capturePhone("playlist", PhoebeScreenshotScenario.Playlist)
    @Test fun phoneArtistDark() = capturePhone("artist", PhoebeScreenshotScenario.Artist)
    @Test fun phoneAlbumDark() = capturePhone("album", PhoebeScreenshotScenario.Album)
    @Test fun phoneSongDark() = capturePhone("song", PhoebeScreenshotScenario.Song)
    @Test fun phoneSearchDark() = capturePhone("search", PhoebeScreenshotScenario.Search)
    @Test fun phonePlayerDark() = capturePhone("player", PhoebeScreenshotScenario.Player)
    @Test fun phonePlayerVisualizerDark() = capturePhone("player-visualizer", PhoebeScreenshotScenario.PlayerVisualizer)
    @Test fun phonePlayerUpNextExpandedDark() = capturePhone("player-upnext-expanded", PhoebeScreenshotScenario.PlayerUpNextExpanded)
    @Test fun phoneSettingsDark() = capturePhone("settings", PhoebeScreenshotScenario.Settings)
    @Test fun phoneSignInDark() = capturePhone("signin", PhoebeScreenshotScenario.SignIn)
    @Test fun phoneSignInProvidersDark() = capturePhone(
        slug = "signin-providers",
        scenario = PhoebeScreenshotScenario.SignInProviders,
        heightDp = 1380,
    )

    @Test fun phoneHomeLight() = capturePhone("home", PhoebeScreenshotScenario.Home, useLightAppearance = true)
    @Test fun phoneHomeExpandedLight() = capturePhone("home-expanded", PhoebeScreenshotScenario.HomeExpanded, useLightAppearance = true)
    @Test fun phoneHomeAccordionsCollapsedLight() = capturePhone("home-accordions-collapsed", PhoebeScreenshotScenario.HomeAccordionsCollapsed, useLightAppearance = true)
    @Test fun phoneHomeAccordionsExpandedLight() = capturePhone("home-accordions-expanded", PhoebeScreenshotScenario.HomeAccordionsExpanded, useLightAppearance = true)
    @Test fun phoneLibraryLight() = capturePhone("library", PhoebeScreenshotScenario.Library, useLightAppearance = true)
    @Test fun phoneSearchLight() = capturePhone("search", PhoebeScreenshotScenario.Search, useLightAppearance = true)
    @Test fun phonePlayerLight() = capturePhone("player", PhoebeScreenshotScenario.Player, useLightAppearance = true)
    @Test fun phonePlayerBlurredArtworkOnLight() = capturePhone("player-blurred-artwork-on", PhoebeScreenshotScenario.PlayerBlurredArtworkOn, useLightAppearance = true)
    @Test fun phonePlayerBlurredArtworkOffLight() = capturePhone("player-blurred-artwork-off", PhoebeScreenshotScenario.PlayerBlurredArtworkOff, useLightAppearance = true)
    @Test fun phonePlayerVisualizerAlchemyLight() = capturePhone("player-visualizer-alchemy", PhoebeScreenshotScenario.PlayerVisualizerAlchemy, useLightAppearance = true)
    @Test fun phonePlayerVisualizerBatteryLight() = capturePhone("player-visualizer-battery", PhoebeScreenshotScenario.PlayerVisualizerBattery, useLightAppearance = true)
    @Test fun phonePlayerVisualizerBarsAndWavesLight() = capturePhone("player-visualizer-bars-and-waves", PhoebeScreenshotScenario.PlayerVisualizerBarsAndWaves, useLightAppearance = true)
    @Test fun phonePlayerVisualizerBlazingColorsLight() = capturePhone("player-visualizer-blazing-colors", PhoebeScreenshotScenario.PlayerVisualizerBlazingColors, useLightAppearance = true)
    @Test fun phonePlayerVisualizerPlenopticLight() = capturePhone("player-visualizer-plenoptic", PhoebeScreenshotScenario.PlayerVisualizerPlenoptic, useLightAppearance = true)
    @Test fun phonePlayerVisualizerVortexSpectrumLight() = capturePhone("player-visualizer-vortex-spectrum", PhoebeScreenshotScenario.PlayerVisualizerVortexSpectrum, useLightAppearance = true)
    @Test fun phonePlayerVisualizerClassicEQLight() = capturePhone("player-visualizer-classic-eq", PhoebeScreenshotScenario.PlayerVisualizerClassicEQ, useLightAppearance = true)
    @Test fun phonePlayerVisualizerHaloSpectrumLight() = capturePhone("player-visualizer-halo-spectrum", PhoebeScreenshotScenario.PlayerVisualizerHaloSpectrum, useLightAppearance = true)
    @Test fun phonePlayerUpNextExpandedLight() = capturePhone("player-upnext-expanded", PhoebeScreenshotScenario.PlayerUpNextExpanded, useLightAppearance = true)

    @Test fun phoneHomeRedTintDark() = capturePhone("home-red-tint", PhoebeScreenshotScenario.Home, tintId = PhoebeTintOption.fromId("red").id)
    @Test fun phoneLibraryRedTintDark() = capturePhone("library-red-tint", PhoebeScreenshotScenario.Library, tintId = PhoebeTintOption.fromId("red").id)
    @Test fun phoneSearchRedTintDark() = capturePhone("search-red-tint", PhoebeScreenshotScenario.Search, tintId = PhoebeTintOption.fromId("red").id)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotTestApplication::class)
class PhoebeAndroidTabletScreenshotTest {
    @Test fun tabletHomeDark() = captureTablet("home", PhoebeScreenshotScenario.Home)
    @Test fun tabletFavoritePlaylistsDark() = captureTablet("favorite-playlists", PhoebeScreenshotScenario.FavoritePlaylists)
    @Test fun tabletFavoriteArtistsDark() = captureTablet("favorite-artists", PhoebeScreenshotScenario.FavoriteArtists)
    @Test fun tabletArtistRadioDark() = captureTablet("artist-radio", PhoebeScreenshotScenario.ArtistRadio)
    @Test fun tabletLibraryDark() = captureTablet("library", PhoebeScreenshotScenario.Library)
    @Test fun tabletLibraryUpNextExpandedDark() = captureTabletUpNextExpanded("library", PhoebeScreenshotScenario.Library)
    @Test fun tabletRadioDark() = captureTablet("radio", PhoebeScreenshotScenario.Radio)
    @Test fun tabletPlaylistDark() = captureTablet("playlist", PhoebeScreenshotScenario.Playlist)
    @Test fun tabletArtistDark() = captureTablet("artist", PhoebeScreenshotScenario.Artist)
    @Test fun tabletSearchDark() = captureTablet("search", PhoebeScreenshotScenario.Search)
    @Test fun tabletSearchUpNextExpandedDark() = captureTabletUpNextExpanded("search", PhoebeScreenshotScenario.Search)
    @Test fun tabletPlayerDark() = captureTablet("player", PhoebeScreenshotScenario.Player)
}

private fun capturePhone(
    slug: String,
    scenario: PhoebeScreenshotScenario,
    useLightAppearance: Boolean = false,
    tintId: String = PhoebeTintOption.Purple.id,
    heightDp: Int = 932,
) = capture(
    name = "android-phone-$slug-${if (useLightAppearance) "light" else "dark"}",
    scenario = scenario,
    widthDp = 430,
    heightDp = heightDp,
    useLightAppearance = useLightAppearance,
    tintId = tintId,
)

private fun captureTablet(
    slug: String,
    scenario: PhoebeScreenshotScenario,
    tintId: String = PhoebeTintOption.Purple.id,
) = capture(
    name = "android-tablet-$slug-dark",
    scenario = scenario,
    widthDp = 1180,
    heightDp = 820,
    tintId = tintId,
)

private fun captureTabletUpNextExpanded(
    slug: String,
    scenario: PhoebeScreenshotScenario,
) = capture(
    name = "android-tablet-$slug-upnext-expanded-dark",
    scenario = scenario,
    widthDp = 1180,
    heightDp = 820,
    forceShowQueue = true,
)

@OptIn(ExperimentalRoborazziApi::class)
private fun capture(
    name: String,
    scenario: PhoebeScreenshotScenario,
    widthDp: Int,
    heightDp: Int,
    useLightAppearance: Boolean = false,
    tintId: String = PhoebeTintOption.Purple.id,
    forceShowQueue: Boolean = false,
) {
    captureRoboImage(
        filePath = "src/screenshotTest/roborazzi/$name.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = widthDp, heightDp = heightDp)
        },
    ) {
        PhoebeScreenshotApp(
            scenario = scenario,
            useLightAppearance = useLightAppearance,
            tintId = tintId,
            forceShowQueue = forceShowQueue,
            modifier = Modifier,
        )
    }
}
