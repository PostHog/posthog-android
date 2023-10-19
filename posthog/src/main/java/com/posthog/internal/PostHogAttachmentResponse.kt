package com.posthog.internal

import com.google.gson.annotations.SerializedName

internal data class PostHogAttachmentResponse(
    val id: Long,
    @SerializedName("attachment_location")
    val attachmentLocation: String,
    val name: String,
)
