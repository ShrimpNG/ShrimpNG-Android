package com.v2ray.ang.dto

data class UrlFetchResult(
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)
