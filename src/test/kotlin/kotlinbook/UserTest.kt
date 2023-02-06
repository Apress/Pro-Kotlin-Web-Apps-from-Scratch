package kotlinbook

import kotliquery.TransactionalSession
import kotliquery.queryOf
import kotliquery.sessionOf
import java.util.*
import kotlin.test.*

val testAppConfig = createAppConfig("test")
val testDataSource = createAndMigrateDataSource(testAppConfig)

class UserTest {
    @Test
    fun testHelloWorld() {
        assertEquals(1, 1)
    }

    @Test
    fun testCreateUser() {
       testTx { dbSess ->
            val userAId = createUser(dbSess,
                email = "augustlilleaas@me.com",
                name = "August Lilleaas",
                passwordText = "1234"
            )

            val userBId = createUser(dbSess,
                email = "august@augustl.com",
                name = "August Lilleaas",
                passwordText = "1234"
            )

            assertNotEquals(userAId, userBId)
        }
    }

    @Test
    fun testCreateAnotherUser() {
        testTx { dbSess ->
            val userId = createUser(dbSess,
                email = "augustlilleaas@me.com",
                name = "August Lilleaas",
                passwordText = "1234"
            )

            // ... write some assertions here ...
        }
    }

    @Test
    fun testListUsers() {
        testTx { dbSess ->
            val usersBefore = listUsers(dbSess)

            val userAId = createUser(dbSess,
                email = "augustlilleaas@me.com",
                name = "August Lilleaas",
                passwordText = "1234")

            val userBId = createUser(dbSess,
                email = "august@augustl.com",
                name = "August Lilleaas",
                passwordText = "1234")

            val users = listUsers(dbSess)
            assertEquals(2, users.size - usersBefore.size)
            assertNotNull(users.find { it.id == userAId })
            assertNotNull(users.find { it.id == userBId })
        }
    }

    @Test
    fun testGetUser() {
        testTx { dbSess ->
            val userId = createUser(dbSess,
                email = "augustlilleaas@me.com",
                name = "August Lilleaas",
                passwordText = "1234",
                tosAccepted = true
            )

            assertNull(getUser(dbSess, -9000))

            val user = getUser(dbSess, userId)
            assertNotNull(user)
            assertEquals(user.email, "augustlilleaas@me.com")
        }
    }

    @Test
    fun testVerifyUserPassword() = testTx { dbSess ->
        val userId = createUser(
            dbSess,
            email = "a@b.com",
            name = "August Lilleaas",
            passwordText = "1234",
            tosAccepted = true
        )

        assertEquals(userId, authenticateUser(dbSess, "a@b.com", "1234"))
        assertEquals(null, authenticateUser(dbSess, "a@b.com", "incorrect"))
        assertEquals(null, authenticateUser(dbSess, "does@not.exist", "1234"))
    }

    @Test
    fun testUserPasswordSalting() = testTx { dbSess ->
        val userAId = createUser(
            dbSess,
            email = "a@b.com",
            name = "A",
            passwordText = "1234",
            tosAccepted = true
        )

        val userBId = createUser(
            dbSess,
            email = "x@b.com",
            name = "X",
            passwordText = "1234",
            tosAccepted = true
        )

        val userAHash = dbSess.single(
            queryOf("SELECT * FROM user_t WHERE id = ?", userAId),
            ::mapFromRow
        )!!["password_hash"] as ByteArray
        val userBHash = dbSess.single(
            queryOf("SELECT * FROM user_t WHERE id = ?", userBId),
            ::mapFromRow
        )!!["password_hash"] as ByteArray

        assertFalse(Arrays.equals(userAHash, userBHash))
    }

}

fun testTx(handler: (dbSess: TransactionalSession) -> Unit) {
    sessionOf(testDataSource, returnGeneratedKey = true).use { dbSess ->
        dbSess.transaction { dbSessTx ->
            try {
                handler(dbSessTx)
            } finally {
                dbSessTx.connection.rollback()
            }
        }
    }
}
