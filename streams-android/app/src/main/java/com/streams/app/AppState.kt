package com.streams.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.data.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide state: subscription status and a pending deep-link destination. */
object AppState {
    private lateinit var appContext: Context

    val hasSubscription = MutableStateFlow(false)

    /** Bumped whenever access may have changed (login/logout/approval) so screens reload. */
    val accessVersion = MutableStateFlow(0)

    /** Set by the Cloud tab's "+" button so Profile scrolls straight to the plans. */
    val scrollToPlans = MutableStateFlow(false)

    /** Set by MainActivity when the admin "set password" e-mail link is opened. */
    val pendingRoute = MutableStateFlow<String?>(null)

    val session: StateFlow<SessionStatus> get() = supabase.auth.sessionStatus

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Re-checks whether the signed-in user may watch Premium (an active plan, or an
     * owner/manager account). With [onlyIfChanged] screens reload only when the answer
     * changed — used on app resume and when a title opens, so a plan approved while the
     * app was open unlocks without a restart. A network error keeps the last known answer.
     */
    suspend fun refreshAccess(onlyIfChanged: Boolean = false) {
        val now = if (supabase.auth.currentUserOrNull() == null) {
            false
        } else {
            runCatching { Repo.hasActiveSubscription() || Repo.myAdminRole() != null }
                .getOrElse { if (onlyIfChanged) return else hasSubscription.value }
        }
        val changed = now != hasSubscription.value
        hasSubscription.value = now
        if (changed || !onlyIfChanged) accessVersion.value++
    }

}

// ---------------------------------------------------------------- small loading helper

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

class Loaded<T>(val state: Load<T>, val reload: () -> Unit)

/** Runs [block] when [keys] change; exposes Loading/Ok/Err plus a reload() for "Try again". */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Loaded<T> {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, attempt) {
        if (state !is Load.Ok) state = Load.Loading
        state = runCatching { block() }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    return Loaded(state) { attempt++ }
}
