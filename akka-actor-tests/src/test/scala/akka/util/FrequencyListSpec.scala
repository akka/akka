/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FrequencyListSpec extends AnyWordSpec with Matchers {

  private def check(frequencyList: FrequencyList[String], expectedLeastToMostFrequent: List[String]): Unit = {
    expectedLeastToMostFrequent.forall(frequencyList.contains)
    frequencyList.size shouldBe expectedLeastToMostFrequent.size
    frequencyList.leastToMostFrequent.toList shouldBe expectedLeastToMostFrequent
    frequencyList.mostToLeastFrequent.toList shouldBe expectedLeastToMostFrequent.reverse
  }

  "FrequencyList" must {

    "track frequency of elements" in {
      val frequency = new FrequencyList[String]

      check(frequency, Nil)

      frequency.update("a")
      check(frequency, List( /* 1: */ "a"))

      frequency.update("b").update("c")
      check(frequency, List( /* 1: */ "a", "b", "c"))

      frequency.update("a").update("c")
      check(frequency, List( /* 1: */ "b", /* 2: */ "a", "c"))

      frequency.update("d").update("e").update("f").update("g")
      check(frequency, List( /* 1: */ "b", "d", "e", "f", "g", /* 2: */ "a", "c"))

      frequency.update("c").update("f")
      check(frequency, List( /* 1: */ "b", "d", "e", "g", /* 2: */ "a", "f", /* 3: */ "c"))

      frequency.remove("d").remove("g").remove("b").remove("f")
      check(frequency, List( /* 1: */ "e", /* 2: */ "a", /* 3: */ "c"))

      frequency.update("e").update("h").update("i")
      check(frequency, List( /* 1: */ "h", "i", /* 2: */ "a", "e", /* 3: */ "c"))

      frequency.removeLeastFrequent(3) shouldBe List("h", "i", "a")
      check(frequency, List( /* 2: */ "e", /* 3: */ "c"))

      frequency.update("j").update("k").update("l").update("m")
      check(frequency, List( /* 1: */ "j", "k", "l", "m", /* 2: */ "e", /* 3: */ "c"))

      frequency.removeLeastFrequent(skip = OptionVal.Some("j")) shouldBe List("k")
      check(frequency, List( /* 1: */ "j", "l", "m", /* 2: */ "e", /* 3: */ "c"))

      frequency.removeLeastFrequent(2, skip = OptionVal.Some("l")) shouldBe List("j", "m")
      check(frequency, List( /* 1: */ "l", /* 2: */ "e", /* 3: */ "c"))

      frequency.update("n").update("o").update("p").update("e").update("o").update("l")
      check(frequency, List( /* 1: */ "n", "p", /* 2: */ "o", "l", /* 3: */ "c", "e"))

      frequency.removeMostFrequent(3) shouldBe List("e", "c", "l")
      check(frequency, List( /* 1: */ "n", "p", /* 2: */ "o"))

      frequency.update("q").update("r").update("p").update("o").update("n")
      check(frequency, List( /* 1: */ "q", "r", /* 2: */ "p", "n", /* 3: */ "o"))

      frequency.removeMostFrequent(skip = OptionVal.Some("o")) shouldBe List("n")
      check(frequency, List( /* 1: */ "q", "r", /* 2: */ "p", /* 3: */ "o"))

      frequency.removeMostFrequent(2, skip = OptionVal.Some("p")) shouldBe List("o", "r")
      check(frequency, List( /* 1: */ "q", /* 2: */ "p"))
    }

  }
}
