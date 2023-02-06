package kotlinbook

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.preprocessor.EnvOrSystemPropertyPreprocessor
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("kotlinbook.MainHoplite")

fun main() {
    log.debug("Starting Hoplite application...")

    val env = System.getenv("KOTLINBOOK_ENV") ?: "local"
    val config = createHopliteAppConfig(env)
    val dataSource = createAndMigrateDataSource(config)

    embeddedServer(Netty, port = 4207) {
        createKtorApplication(config, dataSource)
    }.start(wait = true)
}

fun createHopliteAppConfig(env: String) =
    ConfigLoaderBuilder.default()
        .addResourceSource("/app.conf")
        .addResourceSource("/app-${env}.conf")
        .addPreprocessor(EnvOrSystemPropertyPreprocessor)
        .build()
        .loadConfigOrThrow<WebappConfig>()