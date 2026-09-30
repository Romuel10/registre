package mg.registre.communautaire.data

import android.content.Context
import com.google.firebase.FirebaseApp

object FirebaseBootstrap {
    fun initialize(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return FirebaseApp.initializeApp(context) != null
    }
}
