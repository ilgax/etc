package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.LatexNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ColonDivisionTest {
    @Test fun colonDividesRight() = assertEquals("x = 3", answerOf("12:4=x"))
    @Test fun ratioWithUnknownOnTheLeft() = assertEquals("x = 3", answerOf("x:4=15:20"))
    @Test fun colonAndDotAreLeftToRight() = assertEquals("9", answerOf("12:4\\cdot3"))
    @Test fun colonAfterAFraction() = assertEquals("x = 6", answerOf("\\frac{x}{2}:3=1"))
    @Test fun ratioWithUnknownInTheMiddle() = assertEquals("x = 4", answerOf("3:x=6:8"))
    @Test fun spacedColon() = assertEquals("x = 6", answerOf("2 : 3 = x : 9"))
    @Test fun colonCommand() = assertEquals("x = 3", answerOf("12\\colon4=x"))
    @Test fun sameAsDivSign() = assertEquals(answerOf("12\\div4\\cdot3"), answerOf("12:4\\cdot3"))

    @Test fun normalizerMapsColonToSlash() {
        assertEquals("12/4=x", LatexNormalizer.normalize("12 : 4 = x").latex)
        assertEquals("12/4=x", LatexNormalizer.normalize("12\\colon 4=x").latex)
        // the thin-space command is still just spacing
        assertEquals("2x+3=7", LatexNormalizer.normalize("2x\\:+3=7").latex)
    }

    @Test fun trailingColonIsPunctuation() = assertEquals("2x=4", LatexNormalizer.normalize("2x=4:").latex)
}
