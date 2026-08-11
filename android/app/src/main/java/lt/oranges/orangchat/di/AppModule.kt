package lt.oranges.orangchat.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import lt.oranges.orangchat.crypto.E2eeKeystore
import lt.oranges.orangchat.data.local.TokenStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore =
        TokenStore(context)

    @Provides
    @Singleton
    fun provideE2eeKeystore(@ApplicationContext context: Context): E2eeKeystore =
        E2eeKeystore.get(context)
}
