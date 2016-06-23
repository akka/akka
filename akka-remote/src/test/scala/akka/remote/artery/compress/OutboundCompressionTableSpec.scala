/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor._
import akka.testkit.AkkaSpec

class OutboundCompressionTableSpec extends AkkaSpec {
  import CompressionTestUtils._

  val remoteAddress = Address("artery", "example", "localhost", 0)

  "OutboundCompressionTable" must {
    "not compress not-known values" in {
      val table = new OutboundActorRefCompressionTable(system, remoteAddress)
      table.compress(minimalRef("banana")) should ===(-1)
    }
  }

  "OutboundActorRefCompressionTable" must {
    val alice = minimalRef("alice")
    val bob = minimalRef("bob")

    "always compress /deadLetters" in {
      val table = new OutboundActorRefCompressionTable(system, remoteAddress)
      table.compress(system.deadLetters) should ===(0)
    }

    "not compress unknown actor ref" in {
      val table = new OutboundActorRefCompressionTable(system, remoteAddress)
      table.compress(alice) should ===(-1) // not compressed
    }

    "compress previously registered actor ref" in {
      val table = new OutboundActorRefCompressionTable(system, remoteAddress)
      table.register(alice, 1)
      table.compress(alice) should ===(1) // compressed

      table.compress(bob) should ===(-1) // not compressed
    }

    "fail if same id attempted to be registered twice" in {
      val table = new OutboundActorRefCompressionTable(system, remoteAddress)
      table.register(alice, 1)
      val ex = intercept[AllocatedSameIdMultipleTimesException] {
        table.register(bob, 1)
      }

      ex.getMessage should include("Attempted to allocate compression id [1] second time, " +
        "was already bound to value [Actor[akka://OutboundCompressionTableSpec/alice]], " +
        "tried to bind to [Actor[akka://OutboundCompressionTableSpec/bob]]!")
    }

    "survive compression ahead-allocation, and then fast forward allocated Ids counter when able to (compact storage)" in {
      val table = new OutboundActorRefCompressionTable(system, remoteAddress)
      table.register(alice, 1)
      table.compressionIdAlreadyAllocated(1) should ===(true)

      table.register(bob, 3) // ahead allocated
      table.compressionIdAlreadyAllocated(2) should ===(false)
      table.compressionIdAlreadyAllocated(3) should ===(true)

      table.register(minimalRef("oogie-boogie"), 4) // ahead allocated (we're able to survive re-delivery of allocation messages)
      table.compressionIdAlreadyAllocated(2) should ===(false)
      table.compressionIdAlreadyAllocated(4) should ===(true)

      table.register(minimalRef("jack-skellington"), 2) // missing allocation was re-delivered, cause fast-forward

      table.compressionIdAlreadyAllocated(2) should ===(true)

      table.register(minimalRef("jack-sparrow"), 5) // immediate next, after fast-forward 
    }

    // FIXME "fast forward" concept will not exist once we use "advertise entire table", possibly remove mentions of that
    // TODO cover more cases of holes in the redeliveries of advertisements
    // TODO ^ to cover the fast forward logic a bit more
  }

}
