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

    /**
     * Holds the Keystore-backed identity keys and the conversation keys sealed
     * under them (docs/E2EE.md §3). One instance: the sequence counter that
     * keeps the fixed message nonce safe has to be the same object everywhere.
     */
    @Provides
    @Singleton
    fun provideE2eeKeystore(@ApplicationContext context: Context): E2eeKeystore =
        // Through get() rather than the constructor, so the UI - which cannot be
        // injected everywhere it needs a keystore - shares this exact object and
        // not a second one with its own @Synchronized sequence counter.
        E2eeKeystore.get(context)
}
