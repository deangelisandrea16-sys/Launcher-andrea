package it.andreadeangelis.launcher

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Calcolatrice per la ricerca: + - * / % ( ) e virgola decimale. Nessun eval. */
object Calc {
    private val ALLOWED = Regex("^[0-9+\\-*/x×÷().,%\\s]+$")

    fun evaluate(input: String): String? {
        val s = input.trim()
        if (s.length < 3 || !ALLOWED.matches(s)) return null
        if (s.none { it in "+-*/x×÷%" }) return null
        val norm = s.replace('x', '*').replace('×', '*').replace('÷', '/').replace(',', '.').replace(" ", "")
        return try {
            val p = Parser(norm)
            val v = p.expr()
            if (p.pos != norm.length) return null
            format(v)
        } catch (e: Exception) {
            null
        }
    }

    private fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) throw ArithmeticException()
        val bd = BigDecimal(v).round(MathContext(12)).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString().replace('.', ',')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun expr(): Double {
            var v = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val t = term()
                v = if (op == '+') v + t else v - t
            }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]
                val f = factor()
                v = if (op == '*') v * f else v / f
            }
            return v
        }
        fun factor(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -factor() }
            var v: Double
            if (pos < s.length && s[pos] == '(') {
                pos++
                v = expr()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException()
                pos++
            } else {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (start == pos) throw IllegalArgumentException()
                v = s.substring(start, pos).toDouble()
            }
            if (pos < s.length && s[pos] == '%') { pos++; v /= 100.0 }
            return v
        }
    }
}
