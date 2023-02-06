package kotlinbook

import kotliquery.queryOf
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.util.*
import kotlin.test.*

object UserSpec : Spek({
    describe("User") {
        it("hello world") {
            assertEquals(1, 1)
        }

        it("should be created successfully") {
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

        it("should be listed") {
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

        it("should be gettable after creation") {
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

        describe("passwords") {
            it("should be verifyable") {
                testTx { dbSess ->
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
            }

            it("should  be salted") {
                testTx { dbSess ->
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
        }
    }
})