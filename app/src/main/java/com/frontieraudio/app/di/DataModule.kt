package com.frontieraudio.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    // TODO: Room database and network client bindings (story-1107, story-1108)
}
