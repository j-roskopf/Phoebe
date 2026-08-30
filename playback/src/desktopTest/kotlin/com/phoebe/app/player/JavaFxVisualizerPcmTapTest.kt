package com.phoebe.app.player

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaFxVisualizerPcmTapTest {
    @Test
    fun parecordCommandRequestsLowLatencyRawMono() {
        val command = JavaFxVisualizerPcmTap.parecordCommand(
            parecord = "/usr/bin/parecord",
            source = "@DEFAULT_MONITOR@",
        )

        assertTrue(command.contains("--raw"))
        assertTrue(command.contains("--record"))
        assertTrue(command.contains("--latency-msec=20"))
        assertTrue(command.contains("--channels=1"))
        assertEquals("@DEFAULT_MONITOR@", command.last())
    }

    @Test
    fun ffmpegPulseCommandReadsNamedMonitor() {
        val command = JavaFxVisualizerPcmTap.ffmpegPulseCommand(
            ffmpeg = "/usr/bin/ffmpeg",
            source = "alsa_output.example.monitor",
        )

        assertTrue(command.contains("-f"))
        assertTrue(command.contains("pulse"))
        assertEquals("alsa_output.example.monitor", command[command.indexOf("-i") + 1])
        assertTrue(command.contains("pipe:1"))
    }

    @Test
    fun linuxMonitorSourceIsUsableOnLinux() {
        if (!JavaFxVisualizerPcmTap.isLinuxOs()) return

        val source = JavaFxVisualizerPcmTap.linuxPulseMonitorSource()
        assertTrue(source.isNotBlank())
        assertTrue(source.endsWith(".monitor") || source == "@DEFAULT_MONITOR@")
    }

    @Test
    fun prefersUsrBinParecordOverPath() {
        val resolved = JavaFxVisualizerPcmTap.executable(
            names = listOf("parecord"),
            extraPaths = listOf("/usr/bin/parecord", "/usr/local/bin/parecord"),
        )
        if (resolved == null) return
        if (File("/usr/bin/parecord").canExecute()) {
            assertEquals("/usr/bin/parecord", resolved)
        }
    }

    @Test
    fun pulseSimpleReadsMonitorPcmOnLinux() {
        if (!JavaFxVisualizerPcmTap.isLinuxOs()) return
        if (!LinuxPulseSimple.isAvailable) return

        val connection = LinuxPulseSimple.openRecord(
            device = JavaFxVisualizerPcmTap.linuxPulseMonitorSource(),
            sampleRateHz = JavaFxVisualizerPcmTap.SampleRateHz,
            channels = 1,
            fragmentBytes = 512,
        ) ?: LinuxPulseSimple.openRecord(
            device = JavaFxVisualizerPcmTap.DefaultMonitor,
            sampleRateHz = JavaFxVisualizerPcmTap.SampleRateHz,
            channels = 1,
            fragmentBytes = 512,
        )
        if (connection == null) {
            assertTrue(false, "pa_simple_new should open the default sink monitor")
            return
        }
        try {
            val bytes = ByteArray(512)
            assertTrue(connection.read(bytes), "pa_simple_read should return a PCM fragment")
        } finally {
            connection.close()
        }
    }

    @Test
    fun visualizerTapPublishesLivePcmOnLinux() {
        if (!JavaFxVisualizerPcmTap.isLinuxOs()) return

        val frames = AtomicInteger(0)
        val ready = CountDownLatch(1)
        val tap = JavaFxVisualizerPcmTap(
            ffmpeg = "/usr/bin/ffmpeg".takeIf { File(it).canExecute() },
            publishPcm = { _, _ -> frames.incrementAndGet() },
            onReady = { ready.countDown() },
        )
        tap.start()
        try {
            val connected = ready.await(2, TimeUnit.SECONDS)
            assertTrue(connected, "Linux visualizer tap should become ready from Pulse monitor PCM")
            Thread.sleep(200L)
            assertTrue(frames.get() >= 8, "expected low-latency PCM frames, got ${frames.get()}")
        } finally {
            tap.stop()
        }
    }
}
