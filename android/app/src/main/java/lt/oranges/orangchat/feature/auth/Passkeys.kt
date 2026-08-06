package lt.oranges.orangchat.feature.auth

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The device half of WebAuthn, through Credential Manager.
 *
 * Credential Manager speaks the same JSON the browser does, and so does
 * webauthn-rs on the other end, so nothing here re-models a challenge or a
 * response: they are passed through as text. What this file *does* own is the
 * unwrapping (the server sends `{"publicKey": {...}}`; the platform wants the
 * inside of it) and turning a cancelled sheet into silence.
 *
 * The relying party is the web origin, not the app, which is what makes a
 * passkey created on the site work here and vice versa. That link is asserted by
 * `/.well-known/assetlinks.json` on that origin naming this package and its
 * signing certificate under `delegate_permission/common.get_login_creds` - see
 * deploy/assetlinks.json. Without it every ceremony below fails on the device
 * before it ever reaches the server.
 */
object Passkeys {

    private val json = Json { ignoreUnknownKeys = true }

    /** Raised when the user dismissed the sheet, which is not a failure to report. */
    class Cancelled : Exception()

    /** Raised when the device has no passkey to offer for this site. */
    class NoneAvailable : Exception()

    /**
     * The platform wants the contents of `publicKey`, not the envelope the
     * server sends it in.
     */
    fun optionsOf(challenge: JsonElement?): String? =
        challenge?.jsonObject?.get("publicKey")?.toString()

    /** Enrols a new credential and returns the response as JSON. */
    suspend fun create(context: Context, optionsJson: String): JsonElement {
        val manager = CredentialManager.create(context)
        val response = try {
            manager.createCredential(
                context = context,
                request = CreatePublicKeyCredentialRequest(optionsJson),
            )
        } catch (e: CreateCredentialCancellationException) {
            throw Cancelled()
        } catch (e: CreateCredentialException) {
            throw IllegalStateException(e.errorMessage?.toString() ?: "This device couldn't create a passkey.")
        }
        val created = response as? CreatePublicKeyCredentialResponse
            ?: throw IllegalStateException("This device couldn't create a passkey.")
        return json.parseToJsonElement(created.registrationResponseJson)
    }

    /** Signs a challenge with an existing credential and returns the response. */
    suspend fun get(context: Context, optionsJson: String): JsonElement {
        val manager = CredentialManager.create(context)
        val response = try {
            manager.getCredential(
                context = context,
                request = GetCredentialRequest(
                    listOf(GetPublicKeyCredentialOption(optionsJson)),
                ),
            )
        } catch (e: GetCredentialCancellationException) {
            throw Cancelled()
        } catch (e: NoCredentialException) {
            throw NoneAvailable()
        } catch (e: GetCredentialException) {
            throw IllegalStateException(e.errorMessage?.toString() ?: "This device couldn't use a passkey.")
        }
        val credential = response.credential as? PublicKeyCredential
            ?: throw IllegalStateException("This device couldn't use a passkey.")
        return json.parseToJsonElement(credential.authenticationResponseJson)
    }
}
