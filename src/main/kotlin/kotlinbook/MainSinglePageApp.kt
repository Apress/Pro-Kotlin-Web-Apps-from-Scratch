package kotlinbook

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("kotlinbook.MainSinglePageApp")

fun main() {
    log.debug("Starting Hoplite application...")

    val env = System.getenv("KOTLINBOOK_ENV") ?: "local"
    val config = createHopliteAppConfig(env)
    val dataSource = createAndMigrateDataSource(config)

    embeddedServer(Netty, port = 4207) {
        createKtorSinglePageApplication(config, dataSource)
    }.start(wait = true)
}

fun Application.createKtorSinglePageApplication(appConfig: WebappConfig, dataSource: DataSource) {
    routing {
        singlePageApplication {
            if (appConfig.useFileSystemAssets) {
                filesPath = "src/main/resources/public"
            } else {
                useResources = true
                filesPath = "public"
            }
            defaultPage = "index.html"
        }

        get("/api/foo", webResponse {
            JsonWebResponse(mapOf("foo" to true))
        })

        get("/api/bar", webResponse {
            JsonWebResponse(mapOf("bar" to true))
        })
    }
}