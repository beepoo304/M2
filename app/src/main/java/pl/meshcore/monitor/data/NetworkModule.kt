package pl.meshcore.monitor.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object NetworkModule {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
}
