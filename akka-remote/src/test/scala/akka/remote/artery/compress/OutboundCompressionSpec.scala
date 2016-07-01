/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor._
import akka.testkit.AkkaSpec

class OutboundCompressionSpec extends AkkaSpec {
  import CompressionTestUtils._

  val remoteAddress = Address("artery", "example", "localhost", 0)

  "OutboundCompression" must {
    "not compress not-known values" in {
      val table = new OutboundActorRefCompression(system, remoteAddress)
      table.compress(minimalRef("banana")) should ===(-1)
    }
  }

  "OutboundActorRefCompression" must {
    val alice = minimalRef("alice")
    val bob = minimalRef("bob")

    "always compress /deadLetters" in {
      val table = new OutboundActorRefCompression(system, remoteAddress)
      table.compress(system.deadLetters) should ===(0)
    }

    "not compress unknown actor ref" in {
      val table = new OutboundActorRefCompression(system, remoteAddress)
      table.compress(alice) should ===(-1) // not compressed
    }

    "compress previously registered actor ref" in {
      val compression = new OutboundActorRefCompression(system, remoteAddress)
      val table = CompressionTable(1, Map(system.deadLetters → 0, alice → 1))
      compression.flipTable(table)
      compression.compress(alice) should ===(1) // compressed
      compression.compress(bob) should ===(-1) // not compressed

      val table2 = table.copy(2, map = table.map.updated(bob, 2))
      compression.flipTable(table2)
      compression.compress(alice) should ===(1) // compressed
      compression.compress(bob) should ===(2) // compressed
    }
  }

}
