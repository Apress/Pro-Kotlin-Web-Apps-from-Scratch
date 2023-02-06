package kotlinbook

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.google.gson.Gson
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import kotliquery.Session
import kotliquery.queryOf
import kotliquery.sessionOf
import javax.sql.DataSource

private val dataSource = HikariDataSource()
    .apply {
        jdbcUrl = "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
    }.also {
        migrateDataSource(it)
    }

class GetTestHelloWorld : RequestHandler<Any?, String> {
    override fun handleRequest(input: Any?, context: Context?): String {
        return Gson().toJson(mapOf(
            "statusCode" to 200,
            "headers" to mapOf("contentType" to "text/plain"),
            "body" to "Hello, World!"
        ))
    }
}

class UserEmailSearch : RequestHandler<Map<String, String>, String> {
    companion object {
        init {
            runBlocking {
                serverlessWebResponseDb(dataSource) { dbSess ->
                    dbSess.single(queryOf("SELECT 1"), ::mapFromRow)
                    JsonWebResponse("")
                }
            }
        }
    }

    override fun handleRequest(
        input: Map<String, String>,
        context: Context
    ): String {
        return serverlessWebResponseDb(dataSource) { dbSess ->
            handleUserEmailSearch(dbSess, input["email"])
        }
    }
}

fun handleUserEmailSearch(dbSess: Session, email: Any?): WebResponse {
    return JsonWebResponse(dbSess.single(
        queryOf(
            "SELECT count(*) c FROM user_t WHERE email LIKE ?",
            "%${email}%"
        ),
        ::mapFromRow)
    )
}

fun getAwsLambdaResponse(contentType: String, rawWebResponse: WebResponse, body: String): String {
    return rawWebResponse.header("content-type", contentType)
        .let { webResponse ->
            Gson().toJson(mapOf(
                "statusCode" to webResponse.statusCode,
                "headers" to webResponse.headers,
                "body" to body
            ))
        }
}

fun serverlessWebResponse(handler: suspend () -> WebResponse): String {
    return runBlocking {
        val webResponse = handler()

        when (webResponse) {
            is TextWebResponse -> {
                getAwsLambdaResponse("text/plain; charset=UTF-8", webResponse, webResponse.body)
            }
            is JsonWebResponse -> {
                getAwsLambdaResponse("application/json; charset=UTF-8", webResponse, Gson().toJson(webResponse.body))
            }
            is HtmlWebResponse -> {
                getAwsLambdaResponse(
                    "text/html; charset=UTF-8",
                    webResponse,
                    buildString {
                        appendHTML().html {
                            with(webResponse.body) { apply() }
                        }
                    }
                )
            }
        }
    }
}

fun serverlessWebResponseDb(dataSource: DataSource, handler: suspend (dbSess: Session) -> WebResponse) =
    serverlessWebResponse {
        sessionOf(dataSource, returnGeneratedKey = true).use { dbSess ->
            handler(dbSess)
        }
    }