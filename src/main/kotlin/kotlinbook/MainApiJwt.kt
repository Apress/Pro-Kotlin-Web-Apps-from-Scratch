package kotlinbook

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.gson.Gson
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("kotlinbook.MainApiJwt")

fun main() {
    log.debug("Starting API JWT application...")
    val env = System.getenv("KOTLINBOOK_ENV") ?: "local"
    val config = createAppConfig(env)
    val dataSource = createAndMigrateDataSource(config)

    embeddedServer(Netty, port = 4207) {
        setUpKtorJwtSecurity(config, dataSource)
        createKtorApplication(config, dataSource)
    }.start(wait = true)
}

fun Application.setUpKtorJwtSecurity(appConfig: WebappConfig, dataSource: DataSource) {
    val jwtAudience = "myApp"
    val jwtIssuer = "http://0.0.0.0:4207"

    authentication {
        jwt("jwt-auth") {
            realm = "myApp"
            verifier(
                JWT
                    .require(Algorithm.HMAC256(appConfig.cookieSigningKey))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build())
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience))
                    JWTPrincipal(credential.payload)
                else
                    null
            }
        }
    }

    routing {
        post("/login", webResponseDb(dataSource) { dbSess ->
            val input = Gson().fromJson(
                call.receiveText(), Map::class.java
            )
            val userId = authenticateUser(dbSess, input["username"] as String, input["password"] as String)

            if (userId == null) {
                JsonWebResponse(
                    mapOf("error" to "Invalid username and/or password"),
                    statusCode = 403
                )
            } else {
                val token = JWT.create()
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .withClaim("userId", userId)
                    .withExpiresAt(
                        Date.from(
                            LocalDateTime
                                .now()
                                .plusDays(30)
                                .toInstant(ZoneOffset.UTC)
                        )
                    )
                    .sign(Algorithm.HMAC256(appConfig.cookieSigningKey))

                JsonWebResponse(mapOf("token" to token))
            }
        })

        authenticate("jwt-auth") {
            get("/secret", webResponseDb(dataSource) { dbSess ->
                val userSession = call.principal<JWTPrincipal>()!!
                val userId = userSession.getClaim("userId", Long::class)!!
                val user = getUser(dbSess, userId)!!

                JsonWebResponse(mapOf("hello" to user.email))
            })
        }
    }
}
