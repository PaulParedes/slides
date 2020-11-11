package examples

import noria.*


fun makeClosure(a: Int, b: Long, c: Double, d: Any): Closure<Long> =
        object : Closure<Long> {
            override fun apply(f: Frame): Long {
                return a + b + (c as Int) + d.hashCode()
            }
        }

var global: Int = 0
var barId: Long = 0
var bId: Long = 0


fun Frame.bar(t: Thunk<Int>) =
        expr{
            println("bar")
            barId = id
            global + read(t)
        }

fun Frame.foo(a: Int, b: Thunk<Int>) =
        thunk {
            val j = state { 12 }
            println("foo")
            val x = bar(b)
            val y = expr {
                println("y")
                val i: Int = read(x)
                i + 1
            }
            expr {
                println("foo.expr")
                read(y) + a + read(j)
            }
        }

fun testClosureEquality() {
    val o = Object()
    val a = makeClosure(1, 1, 1.0, o)
    val b = makeClosure(1, 1, 1.0, o)
    val c = makeClosure(1, 2, 1.0, o)

    val d = makeClosure(1, 1, 1.0, "abc")
    val e = makeClosure(1, 1, 1.0, "abc")
    val eq = generateEquality(a.javaClass, 0)

    if (!eq.eq(a, b)) {
        throw AssertionError("Assertion failed")
    }
    if (eq.eq(a, c)) {
        throw AssertionError("Assertion failed")
    }
    if (!eq.eq(d, e)) {
        throw AssertionError("Assertion failed")
    }
}

fun main(args: Array<String>) {
    testClosureEquality()

    var n = NoriaImpl.noria {
        println("root")
        val b = expr {
            println("b")
            bId = id
            global
        }
        foo(1, b)
    }
    val firstRes = n.result
    println("firstRes = ${firstRes}")
    n = n.revaluate(mapOf(bId to null, barId to null))
    val secondRes = n.result
    println("secondRes = ${secondRes}")
    global++
    n = n.revaluate(mapOf(bId to null, barId to null))
    val thirdRes = n.result
    println("thirdRes = ${thirdRes}")
}










