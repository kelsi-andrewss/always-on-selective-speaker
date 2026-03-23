package com.frontieraudio.app.di

import com.frontieraudio.app.BuildConfig
import com.frontieraudio.app.data.remote.AssemblyAiClient
import com.frontieraudio.app.data.remote.LlmPostProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAssemblyAiClient(): AssemblyAiClient =
        AssemblyAiClient(apiKey = BuildConfig.ASSEMBLY_AI_KEY)

    @Provides
    @Singleton
    fun provideLlmPostProcessor(): LlmPostProcessor =
        LlmPostProcessor(apiKey = BuildConfig.OPENAI_KEY)
}
