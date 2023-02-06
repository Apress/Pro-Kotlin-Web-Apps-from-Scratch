package kotlinbook

import com.google.gson.Gson
import io.jooby.*
import kotlinx.coroutines.delay
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import kotliquery.Session
import kotliquery.sessionOf
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import kotlin.time.Duration

private val log = LoggerFactory.getLogger("kotlinbook.MainJooby")

fun main() {
    log.debug("Starting Jooby application...")

    val env = System.getenv("KOTLINBOOK_ENV") ?: "local"
    val config = createAppConfig(env)
    val dataSource = createAndMigrateDataSource(config)

    runApp(arrayOf()) {
        serverOptions {
            port = config.httpPort
            server = "netty"
        }

        sessionStore = SessionStore.signed(config.cookieSigningKey,
            Cookie("joobyCookie")
                .setMaxAge(Duration.parse("30d").inWholeSeconds)
                .setHttpOnly(true)
                .setPath("/")
                .setSecure(config.useSecureCookie)
                .setSameSite(SameSite.LAX))


        coroutine {
            get("/", joobyWebResponse {
                delay(200)
                TextWebResponse("Hello, World!")
            })

            post("/db_test", joobyWebResponseDb(dataSource) { dbSess ->
                val input = Gson().fromJson(ctx.body(String::class.java), Map::class.java)

                handleUserEmailSearch(dbSess, input["email"])
            })

            get("/login", joobyWebResponse {
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
                    val formData = ctx.form()
                    val userId = authenticateUser(dbSess, formData["username"].value(), formData["password"].value())
                    if (userId == null) {
                        ctx.sendRedirect("/login")
                    } else {
                        ctx.session().put("userId", userId)
                        ctx.sendRedirect("/secret")
                    }
                }
            }

            path("") {
                decorator {
                    val userId = ctx.session().get("userId").valueOrNull()
                    if (userId == null) {
                        ctx.sendRedirect("/login")
                    } else {
                        ctx.attribute("userId", userId.toLong())
                        next.apply(ctx)
                    }
                }

                get("/secret", joobyWebResponseDb(dataSource) { dbSess ->
                    val user = getUser(
                        dbSess,
                        ctx.attribute<Long>("userId")!!
                    )!!
                    TextWebResponse("Hello, ${user.email}!")
                })

                get("/logout") {
                    ctx.session().destroy()
                    ctx.sendRedirect("/")
                }
            }
        }
    }
}

fun joobyWebResponse(handler: suspend HandlerContext.() -> WebResponse): suspend HandlerContext.() -> Any {
    return {
        val resp = this.handler()

        ctx.setResponseCode(resp.statusCode)

        for ((name, values) in resp.headers())
            for (value in values)
                ctx.setResponseHeader(name, value)

        when (resp) {
            is TextWebResponse -> {
                ctx.responseType = MediaType.text
                resp.body
            }

            is JsonWebResponse -> {
                ctx.responseType = MediaType.json
                Gson().toJson(resp.body)
            }

            is HtmlWebResponse -> {
                ctx.responseType = MediaType.html
                buildString {
                    appendHTML().html {
                        with(resp.body) { apply() }
                    }
                }
            }
        }
    }
}

fun joobyWebResponseDb(dataSource: DataSource, handler: suspend HandlerContext.(dbSess: Session) -> WebResponse) = joobyWebResponse {
    sessionOf(dataSource, returnGeneratedKey = true).use { dbSess ->
        handler(dbSess)
    }
}