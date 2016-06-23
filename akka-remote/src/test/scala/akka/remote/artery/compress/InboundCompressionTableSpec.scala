/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.Address
import akka.stream.impl.ConstantFun
import akka.testkit.AkkaSpec

class InboundCompressionTableSpec extends AkkaSpec {

  "InboundCompressionTable" must {
    val NoChange: (String, Int) = null

    "invoke callback when compression triggered" in {
      var p: (String, Int) = NoChange
      val heavyHitters = new TopHeavyHitters[String](2)
      val advertiseCompressionId = new AdvertiseCompressionId[String] {
        override def apply(remoteAddress: Address, ref: String, id: Int): Unit =
          p = ref → id
      }
      val table = new InboundCompressionTable[String](system, heavyHitters, ConstantFun.scalaIdentityFunction, advertiseCompressionId)

      table.increment(null, "A", 1L)
      p should ===("A" → 0)

      table.increment(null, "B", 1L)
      p should ===("B" → 1)

      p = NoChange
      table.increment(null, "A", 1L) // again, yet was already compressed (A count == 2), thus no need to compress (call callback) again
      p should ===(NoChange) // no change

      table.increment(null, "B", 1L) // again, yet was already compressed (B count == 2), thus no need to compress (call callback) again
      p should ===(NoChange) // no change

      table.increment(null, "C", 1L) // max hitters = 2; [A=2, B=2] C=1
      p should ===(NoChange) // no change

      table.increment(null, "C", 1L) // max hitters = 2; [A=2, B=2] C=2 – causes compression of C!
      p should ===(NoChange) // no change
      table.increment(null, "C", 1L) // max hitters = 2; [..., C=3] – causes compression of C!
      p should ===("C" → 2) // allocated 

      p = NoChange
      table.increment(null, "A", 1L) // again!
      p should ===(NoChange)

      p = NoChange
      table.increment(null, "B", 1L) // again!
      p should ===(NoChange)

      // and again and again... won't be signalled again since already compressed
      table.increment(null, "A", 1L)
      table.increment(null, "A", 1L)
      table.increment(null, "A", 1L)
      p should ===(NoChange)
    }
  }

}
