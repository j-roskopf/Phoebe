package com.phoebe.app.player

import com.phoebe.app.platform.PhoebeLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference

/**
 * Reads and writes the macOS default output device volume through CoreAudio.
 *
 * The previous implementation shelled out to `osascript` for every read. At a
 * 400 ms poll that forked a process ~2.5 times a second for the life of the app,
 * costing roughly 15% of a core even while idle. These calls are plain C
 * function calls into an already-loaded system framework instead.
 *
 * Returns null from every operation when CoreAudio is unavailable or the default
 * device exposes no software volume control (common for HDMI and some USB DACs),
 * so callers can fall back.
 */
internal object MacCoreAudioVolume {

    /**
     * True when CoreAudio can resolve a default output device, meaning it is the
     * authoritative source for volume on this machine.
     *
     * Deliberately not "a volume was readable": a device that exposes no software
     * volume (HDMI, many USB DACs, aggregates) has none for AppleScript to read
     * either, so falling back to `osascript` there would fork a process per poll
     * to learn nothing. Callers should treat false — not a null read — as the
     * signal to try another mechanism.
     */
    val isAvailable: Boolean
        get() = library?.let { defaultOutputDeviceId(it) != null } == true

    /**
     * Current output volume in 0..1, or null when the default output device has
     * no software volume control.
     */
    fun readVolume(): Float? {
        val lib = library ?: return null
        val deviceId = defaultOutputDeviceId(lib) ?: return null
        for (element in VolumeElements) {
            val address = propertyAddress(PropertyVolumeScalar, ScopeOutput, element)
            if (lib.AudioObjectHasProperty(deviceId, address) == FalseByte) continue
            val value = FloatByReference()
            val size = IntByReference(FloatBytes)
            val status = lib.AudioObjectGetPropertyData(deviceId, address, 0, Pointer.NULL, size, value.pointer)
            if (status == NoError) return value.value.coerceIn(0f, 1f)
        }
        return null
    }

    /** Sets the output volume. Returns false if CoreAudio could not apply it. */
    fun writeVolume(value: Float): Boolean {
        val lib = library ?: return false
        val deviceId = defaultOutputDeviceId(lib) ?: return false
        val clamped = value.coerceIn(0f, 1f)
        var applied = false
        for (element in VolumeElements) {
            val address = propertyAddress(PropertyVolumeScalar, ScopeOutput, element)
            if (lib.AudioObjectHasProperty(deviceId, address) == FalseByte) continue
            val payload = FloatByReference(clamped)
            val status = lib.AudioObjectSetPropertyData(deviceId, address, 0, Pointer.NULL, FloatBytes, payload.pointer)
            if (status == NoError) {
                applied = true
                // The main element covers the whole device; per-channel elements
                // only exist when it does not, so stop once one takes effect.
                if (element == ElementMain) return true
            }
        }
        return applied
    }

    private fun defaultOutputDeviceId(lib: CoreAudioLibrary): Int? {
        val address = propertyAddress(PropertyDefaultOutputDevice, ScopeGlobal, ElementMain)
        val deviceId = IntByReference()
        val size = IntByReference(IntBytes)
        val status = lib.AudioObjectGetPropertyData(SystemObject, address, 0, Pointer.NULL, size, deviceId.pointer)
        if (status != NoError || deviceId.value == UnknownDevice) return null
        return deviceId.value
    }

    private fun propertyAddress(selector: Int, scope: Int, element: Int) =
        AudioObjectPropertyAddress().apply {
            mSelector = selector
            mScope = scope
            mElement = element
            write()
        }

    private val library: CoreAudioLibrary? by lazy {
        runCatching {
            Native.load("CoreAudio", CoreAudioLibrary::class.java)
        }.onFailure { error ->
            PhoebeLog.d("MacCoreAudioVolume") { "CoreAudio unavailable: ${error.message}" }
        }.getOrNull()
    }

    /** Names mirror the C API exactly; JNA resolves symbols by method name. */
    internal interface CoreAudioLibrary : Library {
        /** CoreAudio's `Boolean` is an unsigned char, so take it as a byte, not a JNA int-width boolean. */
        fun AudioObjectHasProperty(inObjectID: Int, inAddress: AudioObjectPropertyAddress): Byte

        fun AudioObjectGetPropertyData(
            inObjectID: Int,
            inAddress: AudioObjectPropertyAddress,
            inQualifierDataSize: Int,
            inQualifierData: Pointer?,
            ioDataSize: IntByReference,
            outData: Pointer,
        ): Int

        fun AudioObjectSetPropertyData(
            inObjectID: Int,
            inAddress: AudioObjectPropertyAddress,
            inQualifierDataSize: Int,
            inQualifierData: Pointer?,
            inDataSize: Int,
            inData: Pointer,
        ): Int
    }

    @Structure.FieldOrder("mSelector", "mScope", "mElement")
    internal class AudioObjectPropertyAddress : Structure() {
        @JvmField var mSelector: Int = 0

        @JvmField var mScope: Int = 0

        @JvmField var mElement: Int = 0
    }

    private const val NoError = 0
    private const val FalseByte: Byte = 0
    private const val SystemObject = 1
    private const val UnknownDevice = 0
    private const val IntBytes = 4
    private const val FloatBytes = 4

    private const val ElementMain = 0
    private val VolumeElements = intArrayOf(ElementMain, 1, 2)

    /** Four-character codes, as CoreAudio spells them. */
    private const val PropertyDefaultOutputDevice = 0x644F7574 // 'dOut'
    private const val PropertyVolumeScalar = 0x766F6C6D // 'volm'
    private const val ScopeGlobal = 0x676C6F62 // 'glob'
    private const val ScopeOutput = 0x6F757470 // 'outp'
}
