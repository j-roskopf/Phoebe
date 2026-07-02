package com.phoebe.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.phoebe.app.data.AppSettingsRepository
import com.phoebe.app.data.ListenBrainzAccountRepository
import com.phoebe.app.data.ListenBrainzClient
import com.phoebe.app.domain.AppSettings
import com.phoebe.app.domain.AudioProcessingSettings
import com.phoebe.app.domain.DownloadPolicySettings
import com.phoebe.app.domain.EqualizerProfile
import com.phoebe.app.domain.NowPlayingVisualizerPreset
import com.phoebe.app.platform.SecureCredentialKey
import com.phoebe.app.testing.FakeSecureCredentialStore
import com.phoebe.app.testing.newInMemoryPhoebeDatabase
import com.phoebe.app.testing.testHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsRepositoryDesktopTest {
    private var driver: SqlDriver? = null

    @After
    fun cleanup() {
        driver?.close()
        driver = null
    }

    @Test
    fun defaultsRestoreWhenNoRowExists() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repository = AppSettingsRepository(db)

        repository.restore()

        assertEquals(AppSettings.Default, repository.settings.value)
    }

    @Test
    fun settingsPersistAndRestore() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d

        AppSettingsRepository(db).run {
            setCrossfadeSeconds(7)
            setScanLibraryOnLaunch(true)
            setNotifyWhenDownloadFinishes(true)
            setPersistEqualizerSettings(true, EqualizerProfile.Default.normalized().withGain(7, 4.5f))
            setPersistVolumeSettings(true, 0.42f)
            setNowPlayingVisualizerInTvFrame(true)
            setBlurredArtworkAppearance(false)
            setFullBleedDetailArtwork(false)
            setTintedBackgroundGradient(false)
        }
        val restored = AppSettingsRepository(db).apply { restore() }

        assertEquals(7, restored.settings.value.crossfadeSeconds)
        assertTrue(restored.settings.value.scanLibraryOnLaunch)
        assertTrue(restored.settings.value.notifyWhenDownloadFinishes)
        assertTrue(restored.settings.value.persistEqualizerSettings)
        assertTrue(restored.settings.value.persistVolumeSettings)
        assertEquals(0.42f, restored.settings.value.savedVolume)
        assertEquals(4.5f, restored.settings.value.equalizerProfile.gainsDb[7])
        assertTrue(restored.settings.value.nowPlayingVisualizerInTvFrame)
        assertFalse(restored.settings.value.blurredArtworkAppearance)
        assertFalse(restored.settings.value.fullBleedDetailArtwork)
        assertFalse(restored.settings.value.tintedBackgroundGradient)
    }

    @Test
    fun visualizerPresetPersistsAndRestores() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d

        AppSettingsRepository(db).setNowPlayingVisualizerPreset(NowPlayingVisualizerPreset.WireframeSpectrum3D)
        val restored = AppSettingsRepository(db).apply { restore() }

        assertEquals(NowPlayingVisualizerPreset.WireframeSpectrum3D, restored.settings.value.nowPlayingVisualizerPreset)
    }

    @Test
    fun invalidVisualizerPresetFallsBackToArtwork() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d

        AppSettingsRepository(db).setNowPlayingVisualizerPreset(NowPlayingVisualizerPreset.Plenoptic)
        d.execute(
            identifier = null,
            sql = "UPDATE AppSettingsRow SET nowPlayingVisualizerPreset = 'FuturePreset'",
            parameters = 0,
        )
        val restored = AppSettingsRepository(db).apply { restore() }

        assertEquals(NowPlayingVisualizerPreset.Artwork, restored.settings.value.nowPlayingVisualizerPreset)
    }

    @Test
    fun crossfadeClampsToSupportedRange() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repository = AppSettingsRepository(db)

        repository.setCrossfadeSeconds(99)
        assertEquals(AppSettings.MaxCrossfadeSeconds, repository.settings.value.crossfadeSeconds)

        repository.setScanLibraryOnLaunch(false)
        repository.setNotifyWhenDownloadFinishes(false)
        assertFalse(repository.settings.value.scanLibraryOnLaunch)
        assertFalse(repository.settings.value.notifyWhenDownloadFinishes)
    }

    @Test
    fun equalizerProfileClampsBeforePersisting() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val repository = AppSettingsRepository(db)

        repository.setEqualizerProfile(
            EqualizerProfile(
                enabled = true,
                bandCount = 31,
                gainsDb = listOf(99f),
            ),
        )

        assertEquals(31, repository.settings.value.equalizerProfile.bandCount)
        assertEquals(12f, repository.settings.value.equalizerProfile.gainsDb.first())
        assertEquals(31, repository.settings.value.equalizerProfile.gainsDb.size)
    }

    @Test
    fun downloadPolicyAndAudioProcessingPersistAndNormalize() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d

        AppSettingsRepository(db).run {
            setDownloadPolicySettings(
                DownloadPolicySettings(
                    maxConcurrentDownloads = 99,
                    wifiOnly = true,
                    notifyOnCompletion = false,
                ),
            )
            setAudioProcessingSettings(
                AudioProcessingSettings(
                    crossfeedEnabled = true,
                    crossfeedAmount = 1.5f,
                    exclusiveMode = true,
                    bitPerfectPreference = true,
                ),
            )
        }
        val restored = AppSettingsRepository(db).apply { restore() }.settings.value

        assertEquals(DownloadPolicySettings.MaxConcurrentDownloads, restored.downloadPolicy.maxConcurrentDownloads)
        assertTrue(restored.downloadPolicy.wifiOnly)
        assertFalse(restored.downloadPolicy.notifyOnCompletion)
        assertEquals(1f, restored.audioProcessing.crossfeedAmount)
        assertFalse(restored.audioProcessing.exclusiveMode)
        assertFalse(restored.audioProcessing.bitPerfectPreference)
    }

    @Test
    fun listenBrainzConnectStoresTokenOnlyInSecureStore() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val settingsRepository = AppSettingsRepository(db)
        val credentialStore = FakeSecureCredentialStore()
        val accountRepository = ListenBrainzAccountRepository(
            client = ListenBrainzClient(
                testHttpClient(
                    MockEngine {
                        respond(
                            content = """{"valid":true,"user_name":"ada","message":"Token valid."}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
                baseUrl = "https://listenbrainz.example",
            ),
            appSettingsRepository = settingsRepository,
            credentialStore = credentialStore,
        )

        accountRepository.connect("super-secret-token")

        assertEquals("super-secret-token", credentialStore.values[SecureCredentialKey.ListenBrainzUserToken])
        assertEquals("ada", settingsRepository.settings.value.listenBrainz.username)
        val row = db.appSettingsQueries.selectCurrent().awaitAsOne()
        assertFalse(row.listenBrainzSettings.contains("super-secret-token"))
    }

    @Test
    fun listenBrainzDisconnectDeletesCredentialAndSettings() = runTest {
        val (db, d) = newInMemoryPhoebeDatabase()
        driver = d
        val settingsRepository = AppSettingsRepository(db)
        val credentialStore = FakeSecureCredentialStore()
        credentialStore.write(SecureCredentialKey.ListenBrainzUserToken, "secret")
        settingsRepository.updateListenBrainzSettings {
            it.copy(enabled = true, username = "ada")
        }
        val accountRepository = ListenBrainzAccountRepository(
            client = ListenBrainzClient(testHttpClient(MockEngine { respond("{}") })),
            appSettingsRepository = settingsRepository,
            credentialStore = credentialStore,
        )

        accountRepository.disconnect()

        assertFalse(settingsRepository.settings.value.listenBrainz.connected)
        assertFalse(credentialStore.values.containsKey(SecureCredentialKey.ListenBrainzUserToken))
    }
}
