package kotlinbook

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

fun main() {
    val cont = createContinuation().createCoroutineUnintercepted(Continuation(EmptyCoroutineContext, { }))
    println("First")
    cont.resume(Unit)
    println("Second")
    cont.resume(Unit)
    println("Third")
    cont.resume(Unit)
    println("Fourth")
    cont.resume(Unit)
}

suspend fun haltHere() =
    suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
        COROUTINE_SUSPENDED
    }

fun createContinuation(): suspend () -> Unit {
    return {
        println("a")
        haltHere()
        println("b")
        println("c")
        haltHere()
        println("d")
    }
}