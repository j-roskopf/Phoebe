package com.phoebe.app.feature.playback

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

private const val RtldNow = 2
private const val RtldGlobal = 0x100

internal fun dlopenGlobal(path: String): Boolean =
    runCatching {
        val linker = Linker.nativeLinker()
        val dlopen = linker.downcallHandle(
            linker.defaultLookup().find("dlopen").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
        Arena.ofConfined().use { arena ->
            val handle = dlopen.invoke(arena.allocateFrom(path), RtldNow or RtldGlobal) as MemorySegment
            handle.address() != 0L
        }
    }.getOrDefault(false)
