package mg.registre.communautaire.data

import mg.registre.communautaire.BuildConfig

object SupabaseConfig {
    val url: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val publishableKey: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    fun isConfigured(): Boolean =
        url.startsWith("https://") && publishableKey.isNotBlank()
}
