package kotlinbook

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.config.RuntimeBeanReference
import org.springframework.context.support.StaticApplicationContext
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("kotlinbook.MainSpringContext")

fun main() {
    log.debug("Starting Spring Context application...")
    val env = System.getenv("KOTLINBOOK_ENV") ?: "local"
    val config = createAppConfig(env)

    log.debug("Setting up Spring Context...")
    val ctx = createApplicationContext(config)
    val dataSource = ctx.getBean("dataSource", DataSource::class.java)

    embeddedServer(Netty, port = config.httpPort) {
        createKtorApplication(config, dataSource)
    }.start(wait = true)
}

class MigratedDataSourceFactoryBean : FactoryBean<DataSource> {
    lateinit var unmigratedDataSource: DataSource

    override fun getObject() =
        unmigratedDataSource.also(::migrateDataSource)

    override fun getObjectType() =
        DataSource::class.java

    override fun isSingleton() =
        true
}


fun createApplicationContext(appConfig: WebappConfig) =
    StaticApplicationContext().apply {
        beanFactory.registerSingleton("appConfig", appConfig)

        registerBean(
            "unmigratedDataSource",
            HikariDataSource::class.java,
            BeanDefinitionCustomizer { bd ->
                bd.propertyValues.apply {
                    add("jdbcUrl", appConfig.dbUrl)
                    add("username", appConfig.dbUser)
                    add("password", appConfig.dbPassword)
                }
            }
        )

        registerBean(
            "dataSource",
            MigratedDataSourceFactoryBean::class.java,
            BeanDefinitionCustomizer { bd ->
                bd.propertyValues.apply {
                    add(
                        "unmigratedDataSource",
                        RuntimeBeanReference("unmigratedDataSource")
                    )
                }
            }
        )

        refresh()
        registerShutdownHook()
    }