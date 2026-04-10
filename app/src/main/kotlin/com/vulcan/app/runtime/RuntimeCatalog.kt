package com.vulcan.app.runtime

/**
 * RUNTIME CATALOG — Single source of truth for every downloadable runtime.
 *
 * All binaries are official ARM64 Linux releases. No cross-compilation.
 * Node.js: official nodejs.org ARM64 tarballs
 * Bun: single static ARM64 binary from oven-sh/bun
 * Deno: static ARM64 binary from denoland/deno
 * Go: official ARM64 tarball
 * Python: portable ARM64 build
 * PRoot: static ARM64 build for process chroot (no kernel module needed)
 */
object RuntimeCatalog {

    data class NativeRuntime(
        val id: String,
        val displayName: String,
        val versions: List<String>,
        val binaryName: String,
        val downloadUrlTemplate: String,    // Use {version} placeholder
        val extractPath: String,            // Inside archive, where the runtime lives
        val sizeEstimateMB: Int,
        val description: String,
        val extraBinaries: List<String> = emptyList()   // npm, npx, etc.
    )

    data class ProotDistro(
        val id: String,
        val displayName: String,
        val downloadUrl: String,
        val sizeEstimateMB: Int,
        val description: String
    )

    // ─── NATIVE RUNTIMES ──────────────────────────────────────────────────────

    val NODE_JS = NativeRuntime(
        id              = "node",
        displayName     = "Node.js",
        versions        = listOf("22.4.0", "20.14.0", "18.20.0"),
        binaryName      = "node",
        downloadUrlTemplate = "https://nodejs.org/dist/v{version}/node-v{version}-linux-arm64.tar.gz",
        extractPath     = "node-v{version}-linux-arm64",
        sizeEstimateMB  = 45,
        description     = "JavaScript runtime. Runs LibreChat, n8n, Gitea, Code Server, etc.",
        extraBinaries   = listOf("npm", "npx", "corepack")
    )

    val BUN = NativeRuntime(
        id              = "bun",
        displayName     = "Bun",
        versions        = listOf("1.1.18", "1.1.0"),
        binaryName      = "bun",
        downloadUrlTemplate = "https://github.com/oven-sh/bun/releases/download/bun-v{version}/bun-linux-aarch64.zip",
        extractPath     = "bun-linux-aarch64",
        sizeEstimateMB  = 50,
        description     = "All-in-one JS runtime. 3x faster than npm, drop-in Node.js replacement.",
        extraBinaries   = listOf("bunx")
    )

    val DENO = NativeRuntime(
        id              = "deno",
        displayName     = "Deno",
        versions        = listOf("1.45.5", "1.44.4"),
        binaryName      = "deno",
        downloadUrlTemplate = "https://github.com/denoland/deno/releases/download/v{version}/deno-aarch64-unknown-linux-gnu.zip",
        extractPath     = ".",
        sizeEstimateMB  = 90,
        description     = "Secure JS/TS runtime by Ryan Dahl. Permissions-first design."
    )

    val PYTHON = NativeRuntime(
        id              = "python",
        displayName     = "Python",
        versions        = listOf("3.12.4", "3.11.9", "3.10.14"),
        binaryName      = "python3",
        downloadUrlTemplate = "https://github.com/indygreg/python-build-standalone/releases/download/20240713/cpython-{version}+20240713-aarch64-unknown-linux-gnu-install_only.tar.gz",
        extractPath     = "python",
        sizeEstimateMB  = 60,
        description     = "Pure Python apps (no C extensions). For C extensions, use PRoot.",
        extraBinaries   = listOf("pip3", "python3")
    )

    val GO = NativeRuntime(
        id              = "go",
        displayName     = "Go",
        versions        = listOf("1.22.5", "1.21.12"),
        binaryName      = "go",
        downloadUrlTemplate = "https://go.dev/dl/go{version}.linux-arm64.tar.gz",
        extractPath     = "go",
        sizeEstimateMB  = 70,
        description     = "Go runtime for pre-compiled or source-based Go apps."
    )

    val JAVA = NativeRuntime(
        id              = "java",
        displayName     = "Java (OpenJDK 17)",
        versions        = listOf("17.0.11+9"),
        binaryName      = "java",
        downloadUrlTemplate = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-{version}/OpenJDK17U-jre_aarch64_linux_hotspot_{version_clean}.tar.gz",
        extractPath     = "jdk-{version}-jre",
        sizeEstimateMB  = 55,
        description     = "OpenJDK 17 JRE. Runs Gitea (older), SonarQube, Nexus, etc."
    )

    val PHP = NativeRuntime(
        id              = "php",
        displayName     = "PHP",
        versions        = listOf("8.3.9", "8.2.21"),
        binaryName      = "php",
        downloadUrlTemplate = "https://github.com/Anonym0usWork1221/Php-For-Android/releases/download/v{version}/php-{version}-aarch64.tar.gz",
        extractPath     = "php-{version}",
        sizeEstimateMB  = 30,
        description     = "PHP CLI. For apps needing PHP without full PRoot."
    )

    val ALL_NATIVE = listOf(NODE_JS, BUN, DENO, PYTHON, GO, JAVA, PHP)

    fun findById(id: String): NativeRuntime? = ALL_NATIVE.find { it.id == id }

    // ─── PROOT DISTROS ────────────────────────────────────────────────────────

    val UBUNTU_22_04 = ProotDistro(
        id              = "ubuntu-22.04",
        displayName     = "Ubuntu 22.04 LTS",
        downloadUrl     = "https://github.com/termux/proot-distro/releases/download/v3.13.0/ubuntu-jammy-aarch64-pd-v3.13.0.tar.xz",
        sizeEstimateMB  = 200,
        description     = "Full Ubuntu. glibc, apt, build tools. Runs anything. (500MB installed)"
    )

    val DEBIAN_12 = ProotDistro(
        id              = "debian-12",
        displayName     = "Debian 12 (Bookworm)",
        downloadUrl     = "https://github.com/termux/proot-distro/releases/download/v3.13.0/debian-bookworm-aarch64-pd-v3.13.0.tar.xz",
        sizeEstimateMB  = 180,
        description     = "Stable Debian. Slightly lighter than Ubuntu. apt ecosystem."
    )

    val ALPINE = ProotDistro(
        id              = "alpine-3.18",
        displayName     = "Alpine 3.18",
        downloadUrl     = "https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/aarch64/alpine-minirootfs-3.18.0-aarch64.tar.gz",
        sizeEstimateMB  = 8,
        description     = "Ultra-minimal (8MB!). musl libc. For simple native binaries."
    )

    val ALL_DISTROS = listOf(UBUNTU_22_04, DEBIAN_12, ALPINE)

    fun findDistroById(id: String): ProotDistro? = ALL_DISTROS.find { it.id == id }

    // ─── PROOT BINARY ────────────────────────────────────────────────────────
    // Bundled in APK assets, extracted on first run
    const val PROOT_ASSET_NAME = "proot-arm64"
    const val CLOUDFLARED_ASSET_NAME = "cloudflared-arm64"
    const val WIREGUARD_ASSET_NAME = "wireguard-go-arm64"

    // ─── RUNTIME DECISION MATRIX ─────────────────────────────────────────────
    fun shouldUseProot(engine: String, nativeAddons: Boolean): Boolean {
        return when {
            nativeAddons -> true                    // Always PRoot for C extensions
            engine == "proot" -> true
            engine !in listOf("node","bun","deno","python","go","java","php","binary") -> true
            else -> false
        }
    }
}
