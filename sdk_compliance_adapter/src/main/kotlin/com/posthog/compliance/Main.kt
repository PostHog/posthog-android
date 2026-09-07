package com.posthog.compliance

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val profile =
        when (System.getenv("SDK_PROFILE") ?: "core") {
            "core" -> CoreProfile
            "server" -> ServerProfile
            else -> error("SDK_PROFILE must be core or server")
        }
    val storage = File(System.getProperty("java.io.tmpdir"), "posthog-compliance-$port")
    embeddedServer(CIO, port = port) { complianceRoutes(profile, port, storage) }.start(wait = true)
}
