package ca.wolfietech.dev.android.ytwear

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest

enum class Login {
    /** No login cookie in the cookies file, or no file. */
    None,

    /** There is a login, and nothing has confirmed or refuted it. A cookies file proves nothing, since sessions expire. */
    Unconfirmed,

    /** The user tried to open Home and YouTube refused this login. Kept until the login changes. */
    Rejected,
}

/**
 * The login the app has, remembered so it doesn't ask YouTube on every launch. The state only
 * changes when the login changes, or when a Home attempt gets YouTube's answer ([markRejected]).
 * A network error or any other failure leaves it alone.
 *
 * "This login" is a hash of the account's SAPISID cookie, which stays put while YouTube refreshes
 * the other cookies, so cookies from a different login start out [Login.Unconfirmed] again.
 */
class LoginState(private val context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    var login by mutableStateOf(compute())
        private set

    /** Whether the start screen offers Home: only while the login is unconfirmed. */
    val showHome get() = login == Login.Unconfirmed

    /** Re-reads the login, for when cookies may have been replaced (resume, or after an import). */
    fun refresh() {
        login = compute()
    }

    /** YouTube refused this login. Remembered until the login changes. */
    fun markRejected() {
        prefs.edit().remove(FORCED).apply()
        loginFingerprint()?.let { prefs.edit().putString(REJECTED, it).apply() }
        login = Login.Rejected
    }

    /**
     * Debug Settings: treat the login as unconfirmed so Home is offered, without asking YouTube.
     * Lasts until a Home attempt is refused ([markRejected]).
     */
    fun force() {
        prefs.edit().putBoolean(FORCED, true).remove(REJECTED).apply()
        login = Login.Unconfirmed
    }

    private fun compute(): Login {
        // Forced from debug Settings: offer Home whatever the cookies say, until a Home attempt is refused.
        if (prefs.getBoolean(FORCED, false)) return Login.Unconfirmed
        val fingerprint = loginFingerprint() ?: return Login.None
        return if (prefs.getString(REJECTED, null) == fingerprint) Login.Rejected else Login.Unconfirmed
    }

    /** A hash identifying the login in the cookies file, or null when there is none. Never logged. */
    private fun loginFingerprint(): String? = runCatching {
        YtDlp.cookieFile(context).takeIf { it.exists() }?.useLines { lines ->
            // Netscape format: domain, flag, path, secure, expiry, name, value, tab-separated.
            lines.map { it.split('\t') }
                .firstOrNull { it.size == 7 && (it[5] == "SAPISID" || it[5] == "__Secure-3PAPISID") }
                ?.get(6)
        }?.let { value ->
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private companion object {
        const val REJECTED = "rejected_login"
        const val FORCED = "forced_unconfirmed"
    }
}
