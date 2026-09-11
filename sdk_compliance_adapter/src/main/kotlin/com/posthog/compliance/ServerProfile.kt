package com.posthog.compliance

import java.io.File

object ServerProfile : SdkProfile {
    override val name = "posthog-java-server"
    override val version = sdkVersion("server")

    override fun create(
        request: InitRequest,
        storage: File,
        observer: Observation,
    ): SdkClient {
        val config =
            com.posthog.server.PostHogConfig(
                request.api_key,
                request.host,
                flushAt = request.flush_at ?: 100,
                flushIntervalSeconds = request.flushSeconds(),
                featureFlagCacheSize = 0,
                localEvaluation = false,
            )
        config.addBeforeSend(observer.beforeSend)
        val sdk = com.posthog.server.PostHog.with(config)
        return object : SdkClient {
            override fun capture(request: CaptureRequest) {
                observer.track {
                    sdk.capture(
                        distinctId = request.distinct_id,
                        event = request.event,
                        properties = request.properties,
                        timestamp = request.date(),
                    )
                }
            }

            override fun flag(request: FlagRequest): Any? =
                observer.track {
                    sdk.evaluateFlags(
                        distinctId = request.distinct_id,
                        groups = request.groups,
                        personProperties = request.person_properties,
                        groupProperties = request.group_properties,
                        flagKeys = listOf(request.key),
                        disableGeoip = request.disable_geoip ?: false,
                    ).getFlag(request.key)
                }

            override fun flush() = sdk.flush()

            override fun close() = sdk.close()
        }
    }
}
