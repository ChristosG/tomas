package gr.dimitris.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import gr.dimitris.app.ui.theme.DimitrisTheme
import gr.dimitris.app.ui.theme.LocalFeedback

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as DimitrisApp).graph
        setContent {
            DimitrisTheme {
                CompositionLocalProvider(LocalAppGraph provides graph, LocalFeedback provides graph.feedback) {
                    AppNav()
                }
            }
        }
    }
}
