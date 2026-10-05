package com.cortinadev.dogmatix.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.service.RommImageAuth
import com.cortinadev.dogmatix.data.service.TlsTrust
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
 * certificate the user trusted.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context, rommAuth: RommImageAuth): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(rommAuth)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "DogmatixPlus/${BuildConfig.VERSION_NAME}").build())
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
            .build()
    }
}
