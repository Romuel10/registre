package mg.registre.communautaire

import android.app.Application
import mg.registre.communautaire.data.FirebaseBootstrap

class RegistreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseBootstrap.initialize(this)
    }
}
