package akka.streams.impl

import org.scalatest.{ ShouldMatchers, FreeSpec }

class EffectSpec extends FreeSpec with ShouldMatchers {
  "Effect.run" - {
    "executes an ExternalEffect" in {
      var i = 0
      val countUp = Effect.externalEffect(i += 1)
      i should be(0)
      Effect.run(countUp)
      i should be(1)
      Effect.run(countUp)
      i should be(2)
    }
    "runs a SingleStep to completion" in {
      var i = 5
      lazy val countDown: Effect = Effect.step { i -= 1; if (i > 0) countDown else Continue }
      i should be(5)
      Effect.run(countDown)
      i should be(0)
    }
    "runs multiple Effects" - {
      "multiple ExternalEffect" in {
        var v1 = 0
        var v2 = 0
        val countUpV1 = Effect.externalEffect(v1 += 1)
        val countUpV2 = Effect.externalEffect(v2 += 1)
        Effect.run(countUpV1 ~ countUpV2)
        v1 should be(1)
        v2 should be(1)
      }
      "when a SingleStep returns Effects" in {
        var v1 = 0
        var v2 = 0
        val countUpV1 = Effect.externalEffect(v1 += 1)
        val countUpV2 = Effect.externalEffect(v2 += 1)

        val step = Effect.step(countUpV1 ~ countUpV2)
        Effect.run(step ~ step)
        v1 should be(2)
        v2 should be(2)
      }
      "nested Effects" in {
        var i = 0
        def transformI(f: Int ⇒ Int): Effect = Effect.externalEffect(i = f(i))
        def setTo(newI: Int) = transformI(_ ⇒ newI)

        Effect.run(setTo(1) ~ (setTo(2) ~ setTo(3)) ~ setTo(4))
        i should be(4)
      }
      "doesn't reorder execution of Effects" in {
        var v = 0
        var stepRun = false
        val countUp = Effect.externalEffect(v += 1)
        val setTo999 = Effect.externalEffect(v = 999)

        val step = Effect.step { stepRun = true; countUp } ~ setTo999
        Effect.run(step)
        stepRun should be(true)
        // the decision to setTo999 was made before the step was run
        // so the result of the step should be executed only afterwards
        v should be(1000)
      }
    }
  }
}
