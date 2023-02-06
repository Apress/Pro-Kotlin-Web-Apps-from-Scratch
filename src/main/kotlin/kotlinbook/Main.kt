package kotlinbook

import arrow.core.Either
import arrow.core.continuations.either
import arrow.core.left
import arrow.core.right
import at.favre.lib.crypto.bcrypt.BCrypt
import com.google.gson.Gson
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.html.*
import kotliquery.*
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import javax.sql.DataSource
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.*
import kotlin.reflect.full.declaredMemberProperties
import kotlin.time.Duration

private val log = LoggerFactory.getLogger("kotlinbook.Main")

fun main() {
    log.debug("Starting application...")

    val env = System.getenv("KOTLINBOOK_ENV") ?: "local"

    log.debug("Loading config for environment ${env}...")
    val config = createAppConfig(env)

    val secretsRegex = "password|secret|key".toRegex(RegexOption.IGNORE_CASE)
    log.debug("Configuration loaded successfully: ${
        WebappConfig::class.declaredMemberProperties
            .sortedBy { it.name }
            .map {
                if (secretsRegex.containsMatchIn(it.name)) {
                    "${it.name} = ${it.get(config).toString().take(2)}*****"
                } else {
                    "${it.name} = ${it.get(config)}"
                }
            }
            .joinToString(separator = "\n")
    }")

    log.debug("Setting up database...")
    val dataSource = createAndMigrateDataSource(config)

    embeddedServer(Netty, port = 9876) {
        routing {
            get("/random_number", webResponse {
                val num = (200L..2000L).random()
                delay(num)
                TextWebResponse(num.toString())
            })

            get("/ping", webResponse {
                TextWebResponse("pong")
            })

            post("/reverse", webResponse {
                TextWebResponse(call.receiveText().reversed())
            })
        }
    }.start(wait = false)

    embeddedServer(Netty, port = 4207) {
        setUpKtorCookieSecurity(config, dataSource)
        createKtorApplication(config, dataSource)
    }.start(wait = true)
}

fun Application.createKtorApplication(appConfig: WebappConfig, dataSource: DataSource) {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            kotlinbook.log.error("An unknown error occurred", cause)

            call.respondText(
                text = "500: $cause",
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    install(CORS) {
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHost("localhost:4207")
        allowHost("www.myapp.com", schemes = listOf("https"))
        allowCredentials = true
    }

    routing {
        static("/") {
            if (appConfig.useFileSystemAssets) {
                files("src/main/resources/public")
            } else {
                resources("public")
            }
        }

        get("/original_ktor_root") {
            call.respondText("Hello, World!")
        }

        get("/", webResponse {
            TextWebResponse("Hello, world!")
        })

        get("/param_test", webResponse {
            TextWebResponse("The param is: ${call.request.queryParameters["foo"]}")
        })

        get("/json_test", webResponse {
            JsonWebResponse(mapOf("foo" to "bar"))
        })

        get("/json_test_with_header", webResponse {
            JsonWebResponse(mapOf("foo" to "bar"))
                .header("X-Test-Header", "Just a test!")
        })

        get("/db_test", webResponseDb(dataSource) { dbSess ->
            JsonWebResponse(
                dbSess.single(queryOf("SELECT 1"), ::mapFromRow)
            )
        })

        get("/coroutine_test", webResponseDb(dataSource) { dbSess ->
            handleCoroutineTest(dbSess)
        })

        get("/html_test_ktor_basic") {
            call.respondHtml {
                head {
                    title("Hello, World!")
                    styleLink("/app.css")
                }
                body {
                    h1 { +"Hello, World!" }
                }
            }
        }

        get("/html_test_ktor_layout") {
            call.respondHtmlTemplate(AppLayout("Hello, world!")) {
                pageBody {
                    h1 {
                        +"Hello, World!"
                    }
                }
            }
        }

        get("/html_webresponse_test", webResponse {
            HtmlWebResponse(AppLayout("Hello, world!").apply {
                pageBody {
                    h1 {
                        +"Hello, readers!"
                    }
                }
            })
        })

        post("/test_json", webResponse {
            either<ValidationError,MyUser> {
                val input = Gson().fromJson(
                    call.receiveText(), Map::class.java
                )
                MyUser(
                    email = validateEmail(input["email"]).bind(),
                    password = validatePassword(input["password"]).bind()
                )
            }.fold(
                { err ->
                    JsonWebResponse(
                        mapOf("error" to err.error),
                        statusCode = 422
                    )
                },
                { user ->
                    // .. do something with `user`
                    JsonWebResponse(mapOf("success" to true))
                }
            )
        })
    }
}

data class UserSession(val userId: Long): Principal

fun Application.setUpKtorCookieSecurity(appConfig: WebappConfig, dataSource: DataSource) {
    install(Sessions) {
        cookie<UserSession>("user-session") {
            transform(
                SessionTransportTransformerEncrypt(
                    hex(appConfig.cookieEncryptionKey),
                    hex(appConfig.cookieSigningKey)
                )
            )
            cookie.maxAge = Duration.parse("30d")
            cookie.httpOnly = true
            cookie.path = "/"
            cookie.secure = appConfig.useSecureCookie
            cookie.extensions["SameSite"] = "lax"
        }
    }

    install(Authentication) {
        session<UserSession>("auth-session") {
            validate { session ->
                session
            }
            challenge {
                call.respondRedirect("/login")
            }
        }
    }

    routing {
        get("/login", webResponse {
            HtmlWebResponse(AppLayout("Log in").apply {
                pageBody {
                    form(method = FormMethod.post, action = "/login") {
                        p {
                            label { +"E-mail" }
                            input(type = InputType.text, name = "username")
                        }

                        p {
                            label { +"Password" }
                            input(type = InputType.password, name = "password")
                        }

                        button(type = ButtonType.submit) { +"Log in" }
                    }
                }
            })
        })

        post("/login") {
            sessionOf(dataSource).use { dbSess ->
                val params = call.receiveParameters()
                val userId = authenticateUser(
                    dbSess,
                    params["username"]!!,
                    params["password"]!!
                )

                if (userId == null) {
                    call.respondRedirect("/login")
                } else {
                    call.sessions.set(UserSession(userId = userId))
                    call.respondRedirect("/secret")
                }
            }
        }

        authenticate("auth-session") {
            get("/logout") {
                call.sessions.clear<UserSession>()
                call.respondRedirect("/login")
            }

            get("/secret", webResponseDb(dataSource) { dbSess ->
                val userSession = call.principal<UserSession>()!!
                val user = getUser(dbSess, userSession.userId)!!

                HtmlWebResponse(
                    AppLayout("Welcome, ${user.email}").apply {
                        pageBody {
                            h1 {
                                +"Hello there, ${user.email}"
                            }

                            p { +"You're logged in." }
                            p {
                                a(href = "/logout") { +"Log out" }
                            }
                        }
                    }
                )
            })
        }
    }
}


suspend fun handleCoroutineTest(dbSess: Session) = coroutineScope {
    val client = HttpClient(CIO)

    val randomNumberRequest = async {
        client.get("http://localhost:9876/random_number").bodyAsText()
    }

    val reverseRequest = async {
        client.post("http://localhost:9876/reverse") {
            setBody(randomNumberRequest.await())
        }.bodyAsText()
    }

    val queryOperation = async {
        val pingPong = client.get("http://localhost:9876/ping").bodyAsText()

        dbSess.single(
            queryOf("SELECT count(*) c from user_t WHERE email != ?", pingPong),
            ::mapFromRow
        )
    }

    TextWebResponse("""
        Random number: ${randomNumberRequest.await()}
        Reversed: ${reverseRequest.await()}
        Query: ${queryOperation.await()}
    """)
}

data class WebappConfig(
    val httpPort: Int,
    val dbUser: String,
    val dbPassword: String,
    val dbUrl: String,
    val useFileSystemAssets: Boolean,
    val useSecureCookie: Boolean,
    val cookieEncryptionKey: String,
    val cookieSigningKey: String
)

fun createAppConfig(env: String) =
    ConfigFactory
        .parseResources("app-${env}.conf")
        .withFallback(ConfigFactory.parseResources("app.conf"))
        .resolve()
        .let {
            WebappConfig(
                httpPort = it.getInt("httpPort"),
                dbUser = it.getString("dbUser"),
                dbPassword = it.getString("dbPassword"),
                dbUrl = it.getString("dbUrl"),
                useFileSystemAssets = it.getBoolean("useFileSystemAssets"),
                useSecureCookie = it.getBoolean("useSecureCookie"),
                cookieEncryptionKey = it.getString("cookieEncryptionKey"),
                cookieSigningKey = it.getString("cookieSigningKey")
            )
        }

sealed class WebResponse {
    abstract val statusCode: Int
    abstract val headers: Map<String, List<String>>
    abstract fun copyResponse(statusCode: Int, headers: Map<String, List<String>>): WebResponse

    fun header(headerName: String, headerValue: String) =
        header(headerName, listOf(headerValue))

    fun header(headerName: String, headerValue: List<String>) =
        copyResponse(
            statusCode,
            headers.plus(Pair(
                headerName,
                headers.getOrDefault(headerName, listOf()).plus(headerValue))
            )
        )

    fun headers(): Map<String, List<String>> =
        headers
            .map { it.key.lowercase() to it.value }
            .fold(mapOf()) { res, (k, v) ->
                res.plus(Pair(k, res.getOrDefault(k, listOf()).plus(v)))
            }

}

data class TextWebResponse(
    val body: String,
    override val statusCode: Int = 200,
    override val headers: Map<String, List<String>> = mapOf()
) : WebResponse() {
    override fun copyResponse(statusCode: Int, headers: Map<String, List<String>>) = copy(body, statusCode, headers)

}

data class JsonWebResponse(
    val body: Any?,
    override val statusCode: Int = 200,
    override val headers: Map<String, List<String>> = mapOf()
) : WebResponse() {
    override fun copyResponse(statusCode: Int, headers: Map<String, List<String>>) = copy(body, statusCode, headers)
}

data class HtmlWebResponse(
    val body: Template<HTML>,
    override val statusCode: Int = 200,
    override val headers: Map<String, List<String>> = mapOf(),
) : WebResponse() {
    override fun copyResponse(statusCode: Int, headers: Map<String, List<String>>) = copy(body, statusCode, headers)
}


fun webResponse(
    handler: suspend PipelineContext<Unit, ApplicationCall>.() -> WebResponse
): PipelineInterceptor<Unit, ApplicationCall> {
    return {
        val resp = this.handler()

        for ((name, values) in resp.headers())
            for (value in values)
                call.response.header(name, value)

        val statusCode = HttpStatusCode.fromValue(resp.statusCode)

        when (resp) {
            is TextWebResponse -> {
                call.respondText(
                    text = resp.body,
                    status = statusCode
                )
            }
            is JsonWebResponse -> {
                call.respond(KtorJsonWebResponse (
                    body = resp.body,
                    status = statusCode
                ))
            }
            is HtmlWebResponse -> {
                call.respondHtml(statusCode) {
                    with(resp.body) { apply() }
                }
            }
        }
    }
}

fun webResponseDb(
    dataSource: DataSource,
    handler: suspend PipelineContext<Unit, ApplicationCall>.(dbSess: Session) -> WebResponse
) = webResponse {
    sessionOf(dataSource, returnGeneratedKey = true).use { dbSess ->
        handler(dbSess)
    }
}

fun webResponseTx(
    dataSource: DataSource,
    handler: suspend PipelineContext<Unit, ApplicationCall>.(dbSess: TransactionalSession) -> WebResponse
) = webResponseDb(dataSource) { dbSess ->
    dbSess.transaction { txSess ->
        handler(txSess)
    }
}

class KtorJsonWebResponse (
    val body: Any?,
    override val status: HttpStatusCode = HttpStatusCode.OK
) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)
    override fun bytes() = Gson().toJson(body).toByteArray(Charsets.UTF_8)
}

fun createDataSource(config: WebappConfig) =
    HikariDataSource().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword
    }

fun migrateDataSource(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("db/migration")
        .table("flyway_schema_history")
        .load()
        .migrate()
}

fun createAndMigrateDataSource(config: WebappConfig) =
    createDataSource(config).also(::migrateDataSource)

fun mapFromRow(row: Row): Map<String, Any?> {
    return row.underlying.metaData
        .let { (1..it.columnCount).map(it::getColumnName) }
        .map { it to row.anyOrNull(it) }
        .toMap()
}

fun <A>dbSavePoint(dbSess: Session, body: () -> A): A {
    val sp = dbSess.connection.underlying.setSavepoint()
    return try {
        body().also {
            dbSess.connection.underlying.releaseSavepoint(sp)
        }
    } catch (e: Exception) {
        dbSess.connection.underlying.rollback(sp)
        throw e
    }
}

data class User(
    val id: Long,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
    val email: String,
    val tosAccepted: Boolean,
    val name: String?,
    val passwordHash: ByteBuffer
) {
    companion object {
        fun fromRow(row: Map<String, Any?>) = User(
            id = row["id"] as Long,
            createdAt = (row["created_at"] as OffsetDateTime)
                .toZonedDateTime(),
            updatedAt = (row["updated_at"] as OffsetDateTime)
                .toZonedDateTime(),
            email = row["email"] as String,
            name = row["name"] as? String,
            tosAccepted = row["tos_accepted"] as Boolean,
            passwordHash = ByteBuffer.wrap(
                row["password_hash"] as ByteArray
            )
        )
    }
}

fun createUser(
    dbSession: Session,
    email: String,
    name: String,
    passwordText: String,
    tosAccepted: Boolean = false
): Long {
    val userId = dbSession.updateAndReturnGeneratedKey(
        queryOf(
            """
            INSERT INTO user_t 
            (email, name, tos_accepted, password_hash) 
            VALUES (:email, :name, :tosAccepted, :passwordHash) 
            """,
            mapOf(
                "email" to email,
                "name" to name,
                "tosAccepted" to tosAccepted,
                "passwordHash" to bcryptHasher.hash(10, passwordText.toByteArray(Charsets.UTF_8))
            )
        )
    )

    return userId!!
}

fun listUsers(dbSession: Session) =
    dbSession
        .list(queryOf("SELECT * FROM user_t"), ::mapFromRow)
        .map(User::fromRow)

fun getUser(dbSess: Session, id: Long): User? {
    return dbSess
        .single(queryOf("SELECT * FROM user_t WHERE id = ?", id), ::mapFromRow)
        ?.let(User::fromRow)
}

val bcryptHasher = BCrypt.withDefaults()
val bcryptVerifier = BCrypt.verifyer()

fun authenticateUser(dbSession: Session, email: String, passwordText: String): Long? {
    return dbSession.single(
        queryOf("SELECT * FROM user_t WHERE email = ?", email),
        ::mapFromRow
    )?.let {
        val pwHash = it["password_hash"] as ByteArray

        if (bcryptVerifier.verify(passwordText.toByteArray(Charsets.UTF_8), pwHash).verified) {
            return it["id"] as Long
        } else {
            return null
        }
    }
}

data class ValidationError(val error: String) {}
data class MyUser(val email: String, val password: String)

fun validateEmail(email: Any?): Either<ValidationError, String> {
    if (email !is String) {
        return ValidationError("E-mail must be set").left()
    }

    if (!email.contains("@")) {
        return ValidationError("Invalid e-mail").left()
    }

    return email.right()
}

fun validatePassword(password: Any?): Either<ValidationError, String> {
    if (password !is String) {
        return ValidationError("Password must be set").left()
    }

    if (password == "1234") {
        return ValidationError("Insecure password").left()
    }

    return password.right()
}

suspend fun signUpUser(
    email: String,
    password: String
): Either<ValidationError, MyUser> =
    either {
        val validEmail = validateEmail(email).bind()
        val validPassword = validatePassword(password).bind()

        MyUser(
            email = validEmail,
            password = validPassword
        )
    }

class MY_TAG(consumer: TagConsumer<*>): HTMLTag(
    "my-tag", consumer, emptyMap(),
    inlineTag = true,
    emptyTag = false
)

fun FlowOrHeadingContent.myTag(block: MY_TAG.() -> Unit = {}) {
    MY_TAG(consumer).visit(block)
}

class AppLayout(val pageTitle: String? = null): Template<HTML> {
    val pageBody = Placeholder<BODY>()

    override fun HTML.apply() {
        val pageTitlePrefix = if (pageTitle == null) {
            ""
        } else {
            "${pageTitle} - "
        }

        head {
            title {
                +"${pageTitlePrefix}KotlinBook"
            }

            styleLink("/app.css")
        }

        body {
            insert(pageBody)
        }
    }
}

fun getRandomBytesHex(length: Int) =
    ByteArray(length)
        .also { SecureRandom().nextBytes(it) }
        .let(::hex)
