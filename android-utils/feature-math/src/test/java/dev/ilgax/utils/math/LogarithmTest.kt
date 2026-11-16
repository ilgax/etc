package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogarithmTest {
    @Test fun baseTwo() = assertEquals("x = 32", answerOf("\\log_2 x=5"))
    @Test fun withDomainAndPower() {
        val r = solved("\\log_3(x-1)=2")
        assertEquals("x = 10", r.answers[0].plain)
        assertEquals("x-1>0", r.steps.first { it.caption.startsWith("Domain") }.latex)
        assertEquals("x-1=3^{2}", r.steps.first { it.caption.startsWith("Rewrite") }.latex)
    }
    @Test fun productRuleAndRejection() {
        val r = solved("\\log x+\\log(x-3)=1")
        assertEquals("x = 5", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Product rule") })
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Check x = -2") && it.contains("rejected") })
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Rejected x = -2") })
    }
    @Test fun equalLogarithms() {
        val r = solved("\\ln(x+1)=\\ln(2x-3)")
        assertEquals("x = 4", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Equal logarithms") })
    }
    @Test fun numericDifference() = assertEquals("3", answerOf("\\log_2 32-\\log_2 4"))
    @Test fun exactFractions() {
        assertEquals("1/2 = 0.5", answerOf("\\log_4 2"))
        assertEquals("3/2 = 1.5", answerOf("\\log_9 27"))
        assertEquals("3", answerOf("\\log 1000"))
        assertEquals("1", answerOf("\\ln e"))
        assertEquals("3", answerOf("\\lg 1000"))
    }
    @Test fun powerRuleAndRejection() {
        val r = solved("2\\log_5 x=\\log_5 9")
        assertEquals("x = 3", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Power rule") })
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Check x = -3") && it.contains("rejected") })
    }
    @Test fun changeOfBase() {
        val r = solved("\\log_2 x+\\log_4 x=3")
        assertEquals("x = 4", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Change of base") })
    }
    @Test fun quotientRule() = assertEquals("x = 10", answerOf("\\log_2(x+6)-\\log_2(x-2)=1"))
    @Test fun logOfAProductNeedsBothFactorsPositive() = assertEquals("x = 4", answerOf("\\log_2(x)+\\log_2(x-2)=3"))
    @Test fun squareInTheLog() = assertEquals("x = ±5", answerOf("\\log_2(x^2-9)=4"))
    @Test fun quadraticInLog() = assertEquals("x = 1/e ≈ 0.368 or x = e² ≈ 7.389", answerOf("(\\ln x)^2-\\ln x-2=0"))
    @Test fun irrationalRootSurvives() = assertEquals("x = (-4 + 3√2)/4 ≈ 0.06", answerOf("\\log_2(x)+\\log_2(x+2)=-3"))
    @Test fun noRootSurvives() = assertEquals("No solution", answerOf("\\ln x=\\ln(-x-1)"))

    @Test fun combinesSameBaseConstants() {
        val r = solved("\\log_2 5+\\log_2 3")
        assertEquals("log_2 15 ≈ 3.907", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Product rule") })
    }
    @Test fun quotientOfConstantsIsExact() = assertEquals("2", answerOf("\\log_2 12-\\log_2 3"))
    @Test fun powerRuleOnConstants() {
        val r = solved("2\\log_3 4+\\log_3 5")
        assertEquals("log_3 80 ≈ 3.989", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Power rule") })
    }
    @Test fun evaluationStepForExactLogs() {
        val r = solved("\\log_2 32-\\log_2 4")
        assertEquals("5-2", r.steps.first { it.caption == "Evaluate each logarithm" }.latex)
    }
    @Test fun unknownBaseIsUnsupported() = assertTrue(failedReason("\\log_x 8=3").contains("unknown base"))
}
