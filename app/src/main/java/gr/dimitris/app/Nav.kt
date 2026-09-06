package gr.dimitris.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import gr.dimitris.app.caregiver.BackupScreen
import gr.dimitris.app.caregiver.CaregiverHomeScreen
import gr.dimitris.app.caregiver.ErrorListScreen
import gr.dimitris.app.caregiver.SettingsScreen
import gr.dimitris.app.caregiver.SyncScreen
import gr.dimitris.app.core.settings.DeviceRole
import gr.dimitris.app.core.settings.RolePick
import gr.dimitris.app.today.RoleScreen
import kotlinx.coroutines.flow.first
import gr.dimitris.app.caregiver.content.ItemEditScreen
import gr.dimitris.app.caregiver.content.ItemListScreen
import gr.dimitris.app.caregiver.insights.AdviceScreen
import gr.dimitris.app.caregiver.progress.ProgressScreen
import gr.dimitris.app.caregiver.scripts.ScriptEditScreen
import gr.dimitris.app.caregiver.scripts.ScriptListScreen
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.talkboard.TalkBoardScreen
import gr.dimitris.app.today.PracticeScreen
import gr.dimitris.app.today.SessionScreen
import gr.dimitris.app.today.TodayScreen

object Routes {
    const val ROLE = "role"
    const val TODAY = "today"
    const val SESSION = "session"
    const val PRACTICE = "practice/{moduleId}"
    fun practice(id: ModuleId) = "practice/${id.name}"
    const val TALKBOARD = "talk"
    const val CAREGIVER = "caregiver"
    const val ITEMS = "caregiver/items"
    const val ITEM_EDIT = "caregiver/items/{itemId}"
    const val NEW_ITEM = "new"
    fun itemEdit(id: String?) = "caregiver/items/${id ?: NEW_ITEM}"
    const val SCRIPTS = "caregiver/scripts"
    const val SCRIPT_EDIT = "caregiver/scripts/{scriptId}"
    const val NEW_SCRIPT = "new"
    fun scriptEdit(id: String?) = "caregiver/scripts/${id ?: NEW_SCRIPT}"
    const val PROGRESS = "caregiver/progress"
    const val ADVICE = "caregiver/advice"
    const val ERRORS = "caregiver/errors"
    const val SETTINGS = "caregiver/settings"
    const val BACKUP = "caregiver/backup"
    const val SYNC = "caregiver/sync"
}

/** How any screen opens the talk board. Null outside [AppNav], so a preview or a test host still renders. */
val LocalOpenTalkBoard = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun AppNav() {
    val graph = LocalAppGraph.current
    // Read once, not collected: the start destination of a NavHost cannot change under it, and a
    // caregiver who switches the role in the settings means it from the next launch, not mid-screen.
    // Null while the read is in flight — one frame of the cream background, and never the wrong screen.
    val pick by produceState<RolePick?>(null, graph) { value = graph.settings.rolePick.first() }
    val start = when {
        pick == null -> return
        !pick!!.chosen -> Routes.ROLE
        pick!!.effective == DeviceRole.CAREGIVER -> Routes.CAREGIVER
        else -> Routes.TODAY
    }

    val nav = rememberNavController()
    // Remembered: a fresh lambda on every recomposition changes a staticCompositionLocalOf value,
    // which throws away and rebuilds the whole NavHost subtree underneath it.
    val openTalkBoard = remember(nav) { { nav.navigate(Routes.TALKBOARD) { launchSingleTop = true } } }
    // A caregiver phone opens on the caregiver home, so «Πίσω στον Δημήτρη» has nothing to pop back
    // to. It goes to Today instead — a caregiver may practise on their own phone, and this is how.
    val toToday = remember(nav) {
        { if (!nav.popBackStack(Routes.TODAY, inclusive = false)) nav.navigate(Routes.TODAY) { launchSingleTop = true } }
    }
    CompositionLocalProvider(LocalOpenTalkBoard provides openTalkBoard) {
        NavHost(nav, startDestination = start) {
            composable(Routes.ROLE) {
                RoleScreen(onChosen = { role ->
                    val next = if (role == DeviceRole.CAREGIVER) Routes.CAREGIVER else Routes.TODAY
                    nav.navigate(next) { popUpTo(Routes.ROLE) { inclusive = true } }
                })
            }
            composable(Routes.TODAY) {
                TodayScreen(
                    onStart = { nav.navigate(Routes.SESSION) },
                    onCaregiver = { nav.navigate(Routes.CAREGIVER) },
                    onPractice = { nav.navigate(Routes.practice(it)) },
                )
            }
            composable(Routes.SESSION) {
                SessionScreen(onDone = { nav.popBackStack(Routes.TODAY, inclusive = false) })
            }
            composable(Routes.PRACTICE) { entry ->
                val id = ModuleId.valueOf(entry.arguments?.getString("moduleId") ?: ModuleId.WORDCOACH.name)
                PracticeScreen(moduleId = id, onDone = { nav.popBackStack(Routes.TODAY, inclusive = false) })
            }
            composable(Routes.TALKBOARD) { TalkBoardScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CAREGIVER) {
                CaregiverHomeScreen(onBack = { toToday() }, onOpen = { nav.navigate(it) })
            }
            composable(Routes.ITEMS) {
                ItemListScreen(onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate(Routes.itemEdit(id)) })
            }
            composable(Routes.ITEM_EDIT) { entry ->
                val id = entry.arguments?.getString("itemId")?.takeIf { it != Routes.NEW_ITEM }
                ItemEditScreen(itemId = id, onClose = { nav.popBackStack() })
            }
            composable(Routes.SCRIPTS) {
                ScriptListScreen(onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate(Routes.scriptEdit(id)) })
            }
            composable(Routes.SCRIPT_EDIT) { entry ->
                val id = entry.arguments?.getString("scriptId")?.takeIf { it != Routes.NEW_SCRIPT }
                ScriptEditScreen(scriptId = id, onClose = { nav.popBackStack() })
            }
            composable(Routes.PROGRESS) {
                ProgressScreen(onBack = { nav.popBackStack() }, onAdvice = { nav.navigate(Routes.ADVICE) })
            }
            composable(Routes.ADVICE) { AdviceScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ERRORS) { ErrorListScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SYNC) { SyncScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.BACKUP) {
                BackupScreen(onBack = { nav.popBackStack() }, onImported = { toToday() })
            }
        }
    }
}
