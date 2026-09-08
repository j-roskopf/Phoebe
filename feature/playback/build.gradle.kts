import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.tasks.testing.Test

plugins {
    id("phoebe.feature")
}

val linuxLibcxxResources = layout.buildDirectory.dir("generated/filament-linux-libcxx-resources")

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        val filamentMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.filament.compose)
            }
        }

        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:listenbrainz"))
                implementation(project(":playback"))
                implementation(project(":ui:media"))
            }
        }
        desktopMain {
            dependsOn(filamentMain)
            resources.srcDir(linuxLibcxxResources)
            dependencies {
                implementation(libs.jnativehook)
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport.native.unixsocket)
            }
        }
        androidMain {
            dependsOn(filamentMain)
        }
        iosMain {
            dependsOn(filamentMain)
        }
    }
}

val fetchLinuxFilamentLibcxx = tasks.register("fetchLinuxFilamentLibcxx") {
    val outputDir = linuxLibcxxResources
    outputs.dir(outputDir)
    inputs.property("libcxxPackage", LinuxFilamentLibcxx.packageId)
    onlyIf { System.getProperty("os.name").lowercase().contains("linux") }
    doLast {
        val dest = outputDir.get().asFile.resolve("filament-linux-libcxx")
        dest.mkdirs()
        LinuxFilamentLibcxx.installInto(dest, layout.buildDirectory.get().asFile.resolve("tmp/filament-linux-libcxx"))
    }
}

tasks.matching { task ->
    val name = task.name
    (name.contains("desktop", ignoreCase = true) && name.contains("ProcessResources", ignoreCase = true)) ||
        name == "compileKotlinDesktop" ||
        name == "compileTestKotlinDesktop"
}.configureEach {
    dependsOn(fetchLinuxFilamentLibcxx)
}

tasks.withType<Test>().configureEach {
    if (!name.contains("desktop", ignoreCase = true)) return@configureEach
    if (!System.getProperty("os.name").lowercase().contains("linux")) return@configureEach
    doFirst {
        val libcxx = layout.buildDirectory
            .dir("generated/filament-linux-libcxx-resources/filament-linux-libcxx")
            .get()
            .asFile
        if (!libcxx.isDirectory) return@doFirst
        val existing = environment["LD_LIBRARY_PATH"] as String?
            ?: System.getenv("LD_LIBRARY_PATH")
        environment(
            "LD_LIBRARY_PATH",
            if (existing.isNullOrBlank()) libcxx.absolutePath else "${libcxx.absolutePath}:$existing",
        )
    }
}

object LinuxFilamentLibcxx {
    const val packageId = "llvm-toolchain-18_18.1.3-1ubuntu1"

    private data class Deb(
        val url: String,
        val sha256: String,
        val soname: String,
    )

    fun installInto(dest: File, workRoot: File) {
        val arch = System.getProperty("os.arch").orEmpty()
        val debs = debsFor(arch)
        workRoot.deleteRecursively()
        workRoot.mkdirs()
        for (deb in debs) {
            val debFile = workRoot.resolve(deb.soname.replace(".so.1", "") + ".deb")
            download(deb.url, debFile, deb.sha256)
            val extracted = extractDeb(debFile, workRoot.resolve(deb.soname))
            val elf = findElf(extracted, "${deb.soname}.0")
                ?: findElf(extracted, deb.soname)
                ?: error("Did not find ${deb.soname} in ${deb.url}")
            dest.resolve(deb.soname).outputStream().use { output ->
                elf.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }

    private fun debsFor(arch: String): List<Deb> {
        val isArm = arch == "aarch64" || arch == "arm64"
        return if (isArm) {
            listOf(
                Deb(
                    url = "http://ports.ubuntu.com/ubuntu-ports/pool/universe/l/llvm-toolchain-18/libunwind-18_18.1.3-1ubuntu1_arm64.deb",
                    sha256 = "be5eff7eaa52c8d88e2a779ff305700708bbaa0753a4290221e44e61342c3cac",
                    soname = "libunwind.so.1",
                ),
                Deb(
                    url = "http://ports.ubuntu.com/ubuntu-ports/pool/universe/l/llvm-toolchain-18/libc++abi1-18_18.1.3-1ubuntu1_arm64.deb",
                    sha256 = "c4e83c341b1df1a70213b13c3a14c5047ab18c97e92eed63e5da7e2e2a9e3d2f",
                    soname = "libc++abi.so.1",
                ),
                Deb(
                    url = "http://ports.ubuntu.com/ubuntu-ports/pool/universe/l/llvm-toolchain-18/libc++1-18_18.1.3-1ubuntu1_arm64.deb",
                    sha256 = "309c5d8419c67f1fac4e5b2e419afe792c8b37730acc9c20c83a07d0c32e3f92",
                    soname = "libc++.so.1",
                ),
            )
        } else {
            listOf(
                Deb(
                    url = "http://archive.ubuntu.com/ubuntu/pool/universe/l/llvm-toolchain-18/libunwind-18_18.1.3-1ubuntu1_amd64.deb",
                    sha256 = "e957574728eaba325760bceaf46fdd35e38cbfa99c1512b4f2c9dbb403dadb4c",
                    soname = "libunwind.so.1",
                ),
                Deb(
                    url = "http://archive.ubuntu.com/ubuntu/pool/universe/l/llvm-toolchain-18/libc++abi1-18_18.1.3-1ubuntu1_amd64.deb",
                    sha256 = "bca9a1c9cda96c2632b723e5b481d03bbb2e8248b42166abdc80bff23474ad79",
                    soname = "libc++abi.so.1",
                ),
                Deb(
                    url = "http://archive.ubuntu.com/ubuntu/pool/universe/l/llvm-toolchain-18/libc++1-18_18.1.3-1ubuntu1_amd64.deb",
                    sha256 = "bfbcab24faa4cef3099b847a3413fc7b940e7b0d7c68b86ec0f6d7975c4cf265",
                    soname = "libc++.so.1",
                ),
            )
        }
    }

    private fun download(url: String, dest: File, sha256: String) {
        dest.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(dest.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == sha256) {
            "Checksum mismatch for $url: expected $sha256 but was $digest"
        }
    }

    private fun extractDeb(deb: File, work: File): File {
        work.mkdirs()
        runCommand(work, "ar", "x", deb.absolutePath)
        val data = work.listFiles()?.firstOrNull { it.name.startsWith("data.tar") }
            ?: error("No data.tar in $deb")
        val tarExtracted = runCatching {
            runCommand(work, "tar", "-xf", data.absolutePath)
        }
        if (tarExtracted.isFailure) {
            runCommand(work, "tar", "-I", "zstd", "-xf", data.absolutePath)
        }
        return work
    }

    private fun findElf(root: File, fileName: String): File? =
        root.walkTopDown().firstOrNull { file ->
            file.isFile &&
                file.name == fileName &&
                !Files.isSymbolicLink(file.toPath())
        }

    private fun runCommand(dir: File, vararg command: String) {
        val process = ProcessBuilder(*command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) {
            "Command ${command.joinToString(" ")} failed ($code): $output"
        }
    }
}
