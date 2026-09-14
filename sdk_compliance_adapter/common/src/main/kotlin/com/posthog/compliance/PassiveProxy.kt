package com.posthog.compliance

import okhttp3.OkHttpClient

private class UpstreamStatus(var code: Int? = null)

internal fun passiveProxyClient(): OkHttpClient =
    OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val status = UpstreamStatus()
            val request = chain.request().newBuilder().tag(UpstreamStatus::class.java, status).build()
            val response = chain.proceed(request)
            response.newBuilder().code(checkNotNull(status.code)).build()
        }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            checkNotNull(chain.request().tag(UpstreamStatus::class.java)).code = response.code
            // OkHttp's 503/Retry-After: 0 follow-up ignores retryOnConnectionFailure(false),
            // including for bodyless requests. Hide the status only from its follow-up layer;
            // the application interceptor restores it before forwarding or observation.
            if (response.code == 503) response.newBuilder().code(200).build() else response
        }
        .build()
