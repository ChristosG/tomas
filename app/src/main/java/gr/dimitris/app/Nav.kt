package gr.dimitris.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import gr.dimitris.app.caregiver.CaregiverHomeScreen
import gr.dimitris.app.caregiver.content.ItemEditScreen
import gr.dimitris.app.caregiver.content.ItemListScreen
import gr.dimitris.app.today.SessionScreen
import gr.dimitris.app.today.TodayScreen

object Routes {
    const val TODAY = "today"
    const val SESSION = "session"
    const val CAREGIVER = "caregiver"
    const val ITEMS = "caregiver/items"
    const val ITEM_EDIT = "caregiver/items/{itemId}"
    const val NEW_ITEM = "new"
    fun itemEdit(id: String?) = "caregiver/items/${id ?: NEW_ITEM}"
    const val ERRORS = "caregiver/errors"
    const val SETTINGS = "caregiver/settings"
    const val BACKUP = "caregiver/backup"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.TODAY) {
        composable(Routes.TODAY) {
            TodayScreen(onStart = { nav.navigate(Routes.SESSION) }, onCaregiver = { nav.navigate(Routes.CAREGIVER) })
        }
        composable(Routes.SESSION) {
            SessionScreen(onDone = { nav.popBackStack(Routes.TODAY, inclusive = false) })
        }
        composable(Routes.CAREGIVER) {
            CaregiverHomeScreen(onBack = { nav.popBackStack(Routes.TODAY, inclusive = false) }, onOpen = { nav.navigate(it) })
        }
        composable(Routes.ITEMS) {
            ItemListScreen(onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate(Routes.itemEdit(id)) })
        }
        composable(Routes.ITEM_EDIT) { entry ->
            val id = entry.arguments?.getString("itemId")?.takeIf { it != Routes.NEW_ITEM }
            ItemEditScreen(itemId = id, onClose = { nav.popBackStack() })
        }
        // Tasks 12–14 add: ERRORS, SETTINGS, BACKUP
    }
}
