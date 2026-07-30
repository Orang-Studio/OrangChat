package lt.oranges.orangchat.di

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.BuildConfig
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.AuthInterceptor
import lt.oranges.orangchat.data.remote.ClientVersionInterceptor
import lt.oranges.orangchat.data.remote.PersistentCookieJar
import lt.oranges.orangchat.data.remote.TokenAuthenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    @Provides
    @Named("baseUrl")
    fun provideBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Singleton
    fun provideCookieJar(cookieJar: PersistentCookieJar): okhttp3.CookieJar = cookieJar

    /** Bare client used only by the authenticator's refresh call - carries the
     *  cookie jar (to send the refresh cookie) but has NO authenticator itself,
     *  so a failed refresh can't recurse. */
    @Provides
    @Singleton
    @Named("refresh")
    fun provideRefreshClient(cookieJar: okhttp3.CookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideAuthenticator(
        tokenStore: lt.oranges.orangchat.data.local.TokenStore,
        @Named("baseUrl") baseUrl: String,
        @Named("refresh") refreshClient: Provider<OkHttpClient>,
        json: Json,
    ): TokenAuthenticator = TokenAuthenticator(
        tokenStore = tokenStore,
        baseUrlProvider = Provider { baseUrl },
        clientProvider = refreshClient,
        json = json,
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        clientVersionInterceptor: ClientVersionInterceptor,
        authenticator: TokenAuthenticator,
        cookieJar: okhttp3.CookieJar,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(authInterceptor)
            .addInterceptor(clientVersionInterceptor)
            .authenticator(authenticator)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Attachments, not API calls. The shared client's timeouts are sized for
     * small JSON round trips - a 1 GB upload over a phone connection would trip
     * the write timeout long before it finished - so uploads get their own with
     * no write/call deadline. Progress callbacks are what tell the UI it's still
     * alive; a stalled connection still fails on the socket read.
     *
     * No logging interceptor either: there's nothing useful to log about a
     * multi-hundred-megabyte body.
     */
    @Provides
    @Singleton
    @Named("upload")
    fun provideUploadClient(
        authInterceptor: AuthInterceptor,
        clientVersionInterceptor: ClientVersionInterceptor,
        authenticator: TokenAuthenticator,
        cookieJar: okhttp3.CookieJar,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(authInterceptor)
            .addInterceptor(clientVersionInterceptor)
            .authenticator(authenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    /**
     * For uploads that go to OrangMove rather than OrangChat. Deliberately
     * separate from [provideUploadClient] rather than sharing it: OrangMove is a
     * no-login service that has no use for an OrangChat session, and this way
     * the access token and refresh cookie are never sent to it at all.
     */
    @Provides
    @Singleton
    @Named("orangmove")
    fun provideOrangMoveClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    /** Public KLIPY media API; never attach an OrangChat session to it. */
    @Provides
    @Singleton
    @Named("klipy")
    fun provideKlipyClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * The update manifest and APK are public static files on the same host, not
     * API calls. Kept off the shared client for the same reason as OrangMove's:
     * a static download has no use for the session, so the access token and
     * refresh cookie are never attached to it. No read deadline either - a
     * 30 MB APK over a phone connection outlives a 30-second one.
     */
    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
        @Named("baseUrl") baseUrl: String,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}
