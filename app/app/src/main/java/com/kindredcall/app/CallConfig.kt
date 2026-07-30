package com.kindredcall.app

object CallConfig {
    val SIGNALING_URLS: List<String> =
        BuildConfig.SIGNALING_URLS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val TURN_HOSTS: List<String> =
        BuildConfig.TURN_HOSTS.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val TURN_USERNAME = BuildConfig.TURN_USER
    val TURN_CREDENTIAL = BuildConfig.TURN_PASS
    val SHARED_TOKEN = BuildConfig.SHARED_TOKEN
}
