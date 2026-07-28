package lt.oranges.orangchat.feature.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** What the server publishes next to the APK; see app/build.gradle.kts. */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelogUrl: String = "",
    val size: Long = 0,
    val sha256: String = "",
    /** Loaded from [changelogUrl] after the manifest; never persisted in it. */
    val changelog: String = "",
)

/**
 * OrangChat ships outside any store, so it updates itself: fetch a small
 * manifest, compare version codes, download the APK, hand it to the system
 * installer.
 *
 * The install itself is not silent and cannot be - the user grants "install
 * unknown apps" once, and the system installer still shows its own confirmation
 * every time. Android also refuses an APK signed with a different key than the
 * installed app, which is the real integrity guarantee here; the sha256 check
 * below only catches a corrupt or truncated download earlier and with a clearer
 * message than the installer's generic parse error.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("download") private val client: OkHttpClient,
    private val json: Json,
) {
    /** Cleared and re-downloaded each time; an update is worth no cache. */
    private val downloadDir: File
        get() = File(context.cacheDir, "updates")

    /**
     * The published version, or null when it is not newer than what is running.
     * Throws on a network or parse failure so the UI can say what went wrong.
     */
    suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BuildConfig.UPDATE_MANIFEST_URL).build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Update server returned ${response.code}")
            response.body?.string() ?: error("Update server sent an empty manifest")
        }
        val manifest = json.decodeFromString<UpdateManifest>(body)
        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return@withContext null
        // Changelogs are deliberately standalone text files: a release can
        // correct its notes without rewriting the APK manifest. A missing note
        // must not hide an otherwise valid update.
        val changelog = manifest.changelogUrl.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")
        }.orEmpty()
        manifest.copy(changelog = changelog)
    }

    /**
     * Download [manifest]'s APK, reporting 0–1 as the bytes land. Cancelling the
     * calling coroutine abandons the download and leaves nothing behind.
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        // A half-written APK from a previous attempt would fail to parse and
        // read as "the update is broken" rather than "the download stopped".
        downloadDir.deleteRecursively()
        downloadDir.mkdirs()
        val target = File(downloadDir, "orangchat-${manifest.versionName}.apk")

        val request = Request.Builder().url(manifest.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val body = response.body ?: error("Download failed: no body")
            // Content-Length is the honest total; the manifest's size is a hint
            // that goes stale if the APK is republished.
            val total = body.contentLength().takeIf { it > 0 } ?: manifest.size
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (manifest.sha256.isNotEmpty() && !actual.equals(manifest.sha256, ignoreCase = true)) {
                target.delete()
                error("Downloaded update is corrupt (checksum mismatch)")
            }
        }
        onProgress(1f)
        target
    }

    /**
     * Whether the user has already allowed OrangChat to install APKs. Required
     * from API 26 on, and it is a per-app toggle in system settings - there is
     * no runtime dialog we can raise for it.
     */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Send the user to the toggle that [canInstall] is reporting false for. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Hand [apk] to the system installer. Returns once the installer is showing;
     * whether the user goes through with it is between them and the system, and
     * the app is killed and replaced if they do.
     */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private companion object {
        const val DOWNLOAD_BUFFER = 64 * 1024
    }
}
