/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import org.scalatest.{ Matchers, WordSpecLike }

class HeavyHittersTest extends WordSpecLike with Matchers {

  "TopNHeavyHitters" must {
    "should work" in {
      val hitters = TopNHeavyHitters[String](3)
      hitters.update("A", 10) shouldBe true
      hitters.items should ===(Set("A"))

      hitters.update("B", 20) shouldBe true
      hitters.items should ===(Set("A", "B"))

      hitters.update("C", 1) shouldBe true
      hitters.items should ===(Set("A", "B", "C"))

      hitters.update("D", 100) shouldBe true
      hitters.items should ===(Set("A", "B", "D"))

      hitters.update("E", 200) shouldBe true
      hitters.items should ===(Set("B", "D", "E"))

      hitters.update("BB", 22) shouldBe true
      hitters.items should ===(Set("BB", "D", "E"))

      hitters.update("a", 1) shouldBe false
      hitters.items should ===(Set("BB", "D", "E"))
    }
  }

  "TopPercentageHeavyHitters" must {
    "should work" in pending
    lazy val PENDING_STILL = {
      val hitters = TopPercentageHeavyHitters[String](0.10)
      hitters.update("A", 10) shouldBe true
      hitters.items should ===(Set("A")) // threshold == 0.1  

      hitters.update("B", 20) shouldBe true
      hitters.items should ===(Set("A", "B")) // threshold == 0.2

      hitters.update("C", 1) shouldBe true
      hitters.items should ===(Set("A", "B", "C")) // threshold == 0.1  

      hitters.update("D", 100) shouldBe true
      hitters.items should ===(Set("A", "B", "D"))

      hitters.update("E", 200) shouldBe true
      hitters.items should ===(Set("B", "D", "E"))

      hitters.update("BB", 22) shouldBe true
      hitters.items should ===(Set("BB", "D", "E"))

      hitters.update("a", 1) shouldBe false
      hitters.items should ===(Set("BB", "D", "E"))
    }

    "should throw if invalid percentage given" in {
      intercept[Exception] { TopPercentageHeavyHitters[String](0.00) }
    }
  }

}
