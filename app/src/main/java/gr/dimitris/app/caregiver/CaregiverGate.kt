package gr.dimitris.app.caregiver

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The caregiver area is not a secret from Dimitris; the gate only prevents accidental entry.
 * When the optional lock is on, this asks for fingerprint/face or the device PIN.
 */
fun canAuthenticate(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

suspend fun authenticateCaregiver(activity: FragmentActivity): Boolean = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
        override fun onAuthenticationFailed() { /* the prompt lets them retry */ }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Λειτουργία φροντιστή")
        .setSubtitle("Ξεκλείδωσε για να συνεχίσεις")
        .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        .build()
    prompt.authenticate(info)
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
}
