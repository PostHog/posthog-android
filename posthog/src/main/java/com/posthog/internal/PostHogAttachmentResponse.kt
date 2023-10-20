package com.posthog.internal

import com.google.gson.annotations.SerializedName

internal data class PostHogAttachmentResponse(
    val id: String,
    @SerializedName("attachment_location")
    val attachmentLocation: String? = null,
    val name: String? = null,
)
