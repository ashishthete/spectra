package com.spectra.app.di

import com.spectra.ai.cloud.CloudCoachingClient
import com.spectra.ai.cloud.CloudCoachingClientInterface
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun bindCloudCoachingClient(
        impl: CloudCoachingClient
    ): CloudCoachingClientInterface
}
