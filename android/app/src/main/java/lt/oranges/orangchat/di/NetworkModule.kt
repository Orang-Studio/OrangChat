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

    @Provides
    @Singleton
    @Named("klipy")
    fun provideKlipyClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

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
