/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import org.scalatest.{ Matchers, WordSpecLike }

class HeavyHittersSpec extends WordSpecLike with Matchers {

  "TopHeavyHitters" must {
    "should work" in {
      val hitters = new TopHeavyHitters[String](3)
      hitters.update("A", 10) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A"))

      hitters.update("B", 20) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A", "B"))

      hitters.update("C", 1) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A", "B", "C"))

      hitters.update("D", 100) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A", "B", "D"))

      hitters.update("E", 200) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("B", "D", "E"))

      hitters.update("BB", 22) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("BB", "D", "E"))

      hitters.update("a", 1) shouldBe false
      hitters.itemsSnapshot.toSet should ===(Set("BB", "D", "E"))
    }

    "correctly replace a hitter" in {
      val hitters = new TopHeavyHitters[String](3)
      hitters.update("A", 10) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A"))

      hitters.update("A", 12) shouldBe false
      hitters.update("A", 22) shouldBe false
      hitters.itemsSnapshot.toSet should ===(Set("A"))
    }

    "correctly drop least heavy hitter when more than N are inserted" in {
      val hitters = new TopHeavyHitters[String](3)

      hitters.update("A", 1) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A"))

      hitters.update("B", 22) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A", "B"))

      hitters.update("C", 33) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("A", "B", "C"))
      hitters.lowestHitterWeight should ===(1)

      // first item which forces dropping least heavy hitter
      hitters.update("D", 100) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("B", "C", "D"))

      // second item which forces dropping least heavy hitter
      hitters.update("X", 999) shouldBe true
      hitters.itemsSnapshot.toSet should ===(Set("X", "C", "D"))
    }

    "replace the right item even when hashCodes collide" in {
      case class MockHashCode(override val toString: String, override val hashCode: Int)
      val hitters = new TopHeavyHitters[MockHashCode](2)

      val a1 = MockHashCode("A", 1)
      val b1 = MockHashCode("B", 1)

      hitters.update(a1, 1)
      hitters.itemsSnapshot.toSet should ===(Set(a1))
      hitters.lowestHitterWeight should ===(0)

      hitters.update(b1, 2)
      hitters.itemsSnapshot.toSet should ===(Set(a1, b1))
      hitters.lowestHitterWeight should ===(1)

      hitters.update(a1, 10)
      hitters.itemsSnapshot.toSet should ===(Set(a1, b1))
      hitters.lowestHitterWeight should ===(2)

      hitters.update(b1, 100)
      hitters.itemsSnapshot.toSet should ===(Set(a1, b1))
      hitters.lowestHitterWeight should ===(10)
    }

    "behave when something drops from being a hitter and comes back" in {
      val hitters = new TopHeavyHitters[String](2)
      hitters.update("A", 1) should ===(true)
      hitters.update("B", 2) should ===(true)
      hitters.update("C", 3) should ===(true) // A was dropped now  
      hitters.update("A", 10) should ===(true) // TODO this is technically unexpected, we have already compressed A...  
    }

  }
}
