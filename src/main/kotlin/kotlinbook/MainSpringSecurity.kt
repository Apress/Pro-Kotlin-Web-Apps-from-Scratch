package kotlinbook

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.servlet.*
import kotliquery.sessionOf
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.ListenerHolder
import org.eclipse.jetty.servlet.ServletContextHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.web.context.support.AbstractRefreshableWebApplicationContext
import org.springframework.web.filter.DelegatingFilterProxy
import javax.servlet.ServletContextEvent
import javax.servlet.ServletContextListener
import javax.servlet.annotation.WebListener
import javax.sql.DataSource

private val log = LoggerFactory.getLogger(
    "kotlinbook.MainSpringSecurity"
)

fun main() {
    val appConfig = createAppConfig(
        System.getenv("KOTLINBOOK_ENV") ?: "local"
    )

    val server = Server()

    val connector = ServerConnector(
        server,
        HttpConnectionFactory()
    )
    connector.port = appConfig.httpPort
    server.addConnector(connector)

    server.handler = ServletContextHandler(
        ServletContextHandler.SESSIONS
    ).apply {
        contextPath = "/"
        resourceBase = System.getProperty("java.io.tmpdir")
        servletContext.setAttribute("appConfig", appConfig)
        servletHandler.addListener(
            ListenerHolder(BootstrapWebApp::class.java)
        )
    }

    server.start()
    server.join()
}

@WebListener
class BootstrapWebApp : ServletContextListener {
    override fun contextInitialized(sce: ServletContextEvent) {
        val ctx = sce.servletContext

        log.debug("Extracting config")
        val appConfig = ctx.getAttribute("appConfig") as WebappConfig

        log.debug("Setting up data source")
        val dataSource = createAndMigrateDataSource(appConfig)

        log.debug("Setting up Ktor servlet environment")
        val appEngineEnvironment = applicationEngineEnvironment {
            module {
                createKtorApplication(appConfig, dataSource)
            }
        }

        val appEnginePipeline = defaultEnginePipeline(appEngineEnvironment)
        BaseApplicationResponse.setupSendPipeline(appEnginePipeline.sendPipeline)
        appEngineEnvironment.monitor.subscribe(ApplicationStarting) {
            it.receivePipeline.merge(appEnginePipeline.receivePipeline)
            it.sendPipeline.merge(appEnginePipeline.sendPipeline)
            it.receivePipeline.installDefaultTransformations()
            it.sendPipeline.installDefaultTransformations()
        }

        ctx.setAttribute(ServletApplicationEngine.ApplicationEngineEnvironmentAttributeKey, appEngineEnvironment)

        log.debug("Setting up Ktor servlet")
        ctx.addServlet("ktorServlet", ServletApplicationEngine::class.java).apply {
            addMapping("/")
        }

        log.debug("Setting up Spring Security")
        val roleHierarchy = """
            ROLE_ADMIN > ROLE_USER
        """

        val wac = object : AbstractRefreshableWebApplicationContext() {
            override fun loadBeanDefinitions(
                beanFactory: DefaultListableBeanFactory
            ) {
                beanFactory.registerSingleton(
                    "dataSource",
                    dataSource
                )
                beanFactory.registerSingleton(
                    "rememberMeKey",
                    "asdf"
                )
                beanFactory.registerSingleton(
                    "roleHierarchy",
                    RoleHierarchyImpl().apply {
                        setHierarchy(roleHierarchy)
                    }
                )

                AnnotatedBeanDefinitionReader(beanFactory)
                    .register(WebappSecurityConfig::class.java)
            }
        }

        wac.servletContext = ctx
        ctx.addFilter(
            "springSecurityFilterChain",
            DelegatingFilterProxy("springSecurityFilterChain", wac)
        ).apply {
            addMappingForServletNames(null, false, "ktorServlet")
        }

    }

    override fun contextDestroyed(sce: ServletContextEvent) {

    }
}

@Configuration
@EnableWebSecurity
open class WebappSecurityConfig {
    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var roleHierarchy: RoleHierarchy

    @Autowired
    lateinit var rememberMeKey: String

    @Autowired
    lateinit var userDetailsService: UserDetailsService

    @Bean
    open fun userDetailsService() =
        UserDetailsService { userName ->
            User(userName, "{noop}", listOf())
        }

    @Bean
    open fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http.authenticationProvider(object : AuthenticationProvider {
            override fun authenticate(auth: Authentication): Authentication? {
                val username = auth.principal as String
                val password = auth.credentials as String

                val userId = sessionOf(dataSource).use { dbSess ->
                    authenticateUser(dbSess, username, password)
                }

                if (userId != null) {
                    return UsernamePasswordAuthenticationToken(
                        username,
                        password,
                        listOf(SimpleGrantedAuthority("ROLE_USER"))
                    )
                }

                if (username == "quentin" && password == "test") {
                    return UsernamePasswordAuthenticationToken(
                        username,
                        password,
                        listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
                    )
                }

                return null
            }

            override fun supports(authentication: Class<*>) =
                authentication == UsernamePasswordAuthenticationToken::class.java
        })

        http
            .authorizeRequests()
            .expressionHandler(DefaultWebSecurityExpressionHandler().apply {
                setRoleHierarchy(roleHierarchy)
            })
            .antMatchers("/login").permitAll()
            .antMatchers("/coroutine_test").permitAll()
            .antMatchers("/admin/**").hasRole("ADMIN")
            .antMatchers("/**").hasRole("USER")
            .anyRequest().authenticated()
            .and().formLogin()
            .and()
            .rememberMe()
            .key(rememberMeKey)
            .rememberMeServices(
                TokenBasedRememberMeServices(rememberMeKey, userDetailsService).apply {
                    setCookieName("REMEMBER_ME_KOTLINBOOK")
                }
            )
            .and()
            .logout()
            .logoutRequestMatcher(AntPathRequestMatcher("/logout"))


        return http.build()
    }
}
