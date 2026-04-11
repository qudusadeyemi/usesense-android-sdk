package ai.usesense.example

import android.app.Application
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseConfig

class ExampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        UseSense.initialize(
            context = applicationContext,
            config = UseSenseConfig(
                // TODO: Replace with your sandbox API key from https://watchtower.usesense.ai
                apiKey = "sk_sandbox_replace_me",
            ),
        )
    }
}
