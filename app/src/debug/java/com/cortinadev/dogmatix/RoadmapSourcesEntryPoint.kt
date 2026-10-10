package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RoadmapSourcesEntryPoint {
    fun database(): DogmatixDatabase
    fun sources(): SourcesRepository
}
