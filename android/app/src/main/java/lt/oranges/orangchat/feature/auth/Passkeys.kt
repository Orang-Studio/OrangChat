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

object Passkeys {

    private val json = Json { ignoreUnknownKeys = true }

    class Cancelled : Exception()

    class NoneAvailable : Exception()

    fun optionsOf(challenge: JsonElement?): String? =
        challenge?.jsonObject?.get("publicKey")?.toString()

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
