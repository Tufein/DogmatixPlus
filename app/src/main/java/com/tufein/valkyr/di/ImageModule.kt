package com.tufein.valkyr.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.EventListener
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tufein.valkyr.util.PerfLog
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.data.service.RommImageAuth
import com.tufein.valkyr.data.service.ScreenScraperImageAuth
import com.tufein.valkyr.data.service.TlsTrust
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 5.0: one image loader for the whole app (covers in lists, details, downloads, the second
 * screen). A disk cache keeps covers across starts; RomM images are signed and may use the
 * certificate the user trusted; ScreenScraper images get the user's credentials on the way out.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context, rommAuth: RommImageAuth, screenScraperAuth: ScreenScraperImageAuth): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(rommAuth)
            // 4.3.0: ScreenScraper covers are stored without credentials; this adds them to the request.
            .addInterceptor(screenScraperAuth)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Valkyr/${BuildConfig.VERSION_NAME}").build())
            }
            .sslSocketFactory(TlsTrust.liveSocketFactory, TlsTrust.liveTrustManager)
            .hostnameVerifier(TlsTrust.liveHostnameVerifier)
            .build()
        return ImageLoader.Builder(context)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            // Box art servers send short cache headers; a cover does not change.
            .respectCacheHeaders(false)
            // 4.5.0: counts where each cover came from (memory, disk, network) for the Performance block of the diagnostics.
            .eventListener(object : EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    when (result.dataSource) {
                        DataSource.MEMORY_CACHE, DataSource.MEMORY -> PerfLog.coverLoaded(PerfLog.CoverSource.MEMORY)
                        DataSource.DISK -> PerfLog.coverLoaded(PerfLog.CoverSource.DISK)
                        DataSource.NETWORK -> PerfLog.coverLoaded(PerfLog.CoverSource.NETWORK)
                    }
                }
            })
            .build()
    }
}
