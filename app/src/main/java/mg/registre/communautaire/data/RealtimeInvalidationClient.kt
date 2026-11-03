package mg.registre.communautaire.data

import android.content.Context
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import mg.registre.communautaire.security.AuthSessionManager

class RealtimeInvalidationClient(context: Context) {
    private val auth = AuthSessionManager(context)
    private val client = createSupabaseClient(
        supabaseUrl = SupabaseConfig.url,
        supabaseKey = SupabaseConfig.publishableKey,
    ) {
        accessToken = { auth.currentAccessTokenBlocking() }
        install(Realtime)
    }

    fun registerChanges(): Flow<Unit> = flow {
        val channel = client.channel("registre-entries")
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "registre_entries"
        }.map { Unit }

        channel.subscribe()
        try {
            emitAll(changes)
        } finally {
            runCatching { channel.unsubscribe() }
        }
    }
}
