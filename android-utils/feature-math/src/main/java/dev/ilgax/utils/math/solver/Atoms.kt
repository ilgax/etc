package dev.ilgax.utils.math.solver

/**
 * Pieces the polynomial engine cannot hold: a root of an expression with the unknown, a constant raised to a power
 * of the unknown, a logarithm of an expression with the unknown. Each one is stood in for by a private-use
 * placeholder symbol inside [Poly], so sums, products and cancelling keep working; the atom solvers then isolate
 * the symbol and apply the inverse operation.
 */
internal sealed class Atom(val sym: Char, val tex: String) {
    /** n-th root of [arg] (a polynomial). */
    class Rad(sym: Char, tex: String, val index: Int, val arg: Poly) : Atom(sym, tex)

    /** [base]^[exp] with a positive constant base and an exponent that depends on the unknown. */
    class Pow(sym: Char, tex: String, val base: Surd, val exp: RatFn) : Atom(sym, tex)

    /** log_[base] of [arg]; ln is base e. */
    class Log(sym: Char, tex: String, val base: Surd, val arg: RatFn) : Atom(sym, tex)

    val isConstant: Boolean
        get() = when (this) {
            is Rad -> arg.variables.isEmpty()
            is Pow -> exp.variables.isEmpty()
            is Log -> arg.variables.isEmpty()
        }
}

internal class AtomTable {
    val list = ArrayList<Atom>()
    val isEmpty get() = list.isEmpty()

    operator fun get(c: Char): Atom? = if (isAtomSym(c)) list.getOrNull(c - FIRST) else null

    private fun same(a: RatFn, b: RatFn): Boolean {
        val x = a.simplified().value
        val y = b.simplified().value
        return x.num == y.num && x.den == y.den
    }

    private fun nextSym(): Char = (FIRST + list.size).also { check(list.size < 6000) }

    fun rad(index: Int, arg: Poly, tex: String): Atom.Rad {
        list.firstOrNull { it is Atom.Rad && it.index == index && it.arg == arg }?.let { return it as Atom.Rad }
        return Atom.Rad(nextSym(), tex, index, arg).also { list += it }
    }

    fun pow(base: Surd, exp: RatFn, tex: String): Atom.Pow {
        list.firstOrNull { it is Atom.Pow && it.base == base && same(it.exp, exp) }?.let { return it as Atom.Pow }
        return Atom.Pow(nextSym(), tex, base, exp).also { list += it }
    }

    fun log(base: Surd, arg: RatFn, tex: String): Atom.Log {
        list.firstOrNull { it is Atom.Log && it.base == base && same(it.arg, arg) }?.let { return it as Atom.Log }
        return Atom.Log(nextSym(), tex, base, arg).also { list += it }
    }

    companion object {
        const val FIRST = ''
        fun isAtomSym(c: Char) = c in ''..''
    }
}

/** Variables that the user wrote (not atom placeholders). */
internal val Set<Char>.userVars: Set<Char> get() = filterTo(sortedSetOf()) { !AtomTable.isAtomSym(it) }
internal val Set<Char>.atomSyms: Set<Char> get() = filterTo(sortedSetOf()) { AtomTable.isAtomSym(it) }
