package com.tufein.valkyr

import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.repository.SourcesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RoadmapSourcesEntryPoint {
    fun database(): DogmatixDatabase
    fun sources(): SourcesRepository
}
