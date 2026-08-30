package com.phoebe.app.player

import com.phoebe.app.platform.PhoebeLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference

/**
 * In-process Pulse/PipeWire record of a sink monitor. Spawning parecord from the
 * JVM is unreliable (Filament `LD_LIBRARY_PATH`, closed stdin, sandbox PATH),
 * which left Linux on JavaFX/GStreamer's 1–2 Hz spectrum.
 */
internal object LinuxPulseSimple {
    fun openRecord(
        device: String,
        sampleRateHz: Int,
        channels: Int,
        fragmentBytes: Int,
    ): Connection? {
        val lib = library ?: return null
        require(fragmentBytes > 0)
        val spec = PaSampleSpec().apply {
            format = PaSampleS16le
            rate = sampleRateHz
            this.channels = channels.toByte()
            write()
        }
        val attr = PaBufferAttr().apply {
            maxlength = PaInvalid
            tlength = PaInvalid
            prebuf = PaInvalid
            minreq = PaInvalid
            fragsize = fragmentBytes
            write()
        }
        val error = IntByReference(0)
        val simple = lib.pa_simple_new(
            null,
            "Phoebe",
            PaStreamRecord,
            device,
            "visualizer-monitor",
            spec,
            Pointer.NULL,
            attr,
            error,
        )
        if (simple == null || simple == Pointer.NULL) {
            PhoebeLog.d("LinuxPulseSimple") {
                "pa_simple_new($device) failed: ${error.value}"
            }
            return null
        }
        PhoebeLog.d("LinuxPulseSimple") { "recording Pulse monitor $device" }
        return Connection(lib, simple)
    }

    class Connection(
        private val lib: PulseSimpleLibrary,
        private val simple: Pointer,
    ) {
        fun read(bytes: ByteArray): Boolean {
            val error = IntByReference(0)
            val status = lib.pa_simple_read(simple, bytes, bytes.size.toLong(), error)
            return status >= 0
        }

        fun close() {
            runCatching { lib.pa_simple_free(simple) }
        }
    }

    internal val isAvailable: Boolean
        get() = library != null

    private val library: PulseSimpleLibrary? by lazy {
        val names = listOf(
            "pulse-simple",
            "libpulse-simple.so.0",
            "/usr/lib/libpulse-simple.so.0",
            "/usr/lib64/libpulse-simple.so.0",
        )
        names.firstNotNullOfOrNull { name ->
            runCatching { Native.load(name, PulseSimpleLibrary::class.java) }
                .onFailure { error ->
                    PhoebeLog.d("LinuxPulseSimple") { "load $name failed: ${error.message}" }
                }
                .getOrNull()
        }
    }

    internal interface PulseSimpleLibrary : Library {
        fun pa_simple_new(
            server: String?,
            name: String,
            dir: Int,
            dev: String?,
            streamName: String,
            ss: PaSampleSpec,
            map: Pointer?,
            attr: PaBufferAttr?,
            error: IntByReference,
        ): Pointer?

        fun pa_simple_read(
            simple: Pointer,
            data: ByteArray,
            bytes: Long,
            error: IntByReference,
        ): Int

        fun pa_simple_free(simple: Pointer)
    }

    @Structure.FieldOrder("format", "rate", "channels", "pad0", "pad1", "pad2")
    class PaSampleSpec : Structure() {
        @JvmField var format: Int = PaSampleS16le
        @JvmField var rate: Int = 22_050
        @JvmField var channels: Byte = 1
        @JvmField var pad0: Byte = 0
        @JvmField var pad1: Byte = 0
        @JvmField var pad2: Byte = 0
    }

    @Structure.FieldOrder("maxlength", "tlength", "prebuf", "minreq", "fragsize")
    class PaBufferAttr : Structure() {
        @JvmField var maxlength: Int = PaInvalid
        @JvmField var tlength: Int = PaInvalid
        @JvmField var prebuf: Int = PaInvalid
        @JvmField var minreq: Int = PaInvalid
        @JvmField var fragsize: Int = 512
    }

    private const val PaStreamRecord = 2
    private const val PaSampleS16le = 3
    private const val PaInvalid = -1
}
