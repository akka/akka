/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import akka.io.dns.CachePolicy._
import org.scalatest._

import scala.concurrent.duration._

class CachePolicySpec extends FlatSpec with Matchers {

  it must "have an Ordering for CachePolicy" in {

    val ttl1 = Ttl.fromPositive(1.second)
    val ttl2 = Ttl.fromPositive(2.seconds)
    val ttl3 = Ttl.fromPositive(3.seconds)

    val ordered = Never :: ttl1 :: Forever :: Nil

    ordered.sorted shouldBe ordered

    val unordered = Forever :: Never :: ttl1 :: Nil

    unordered.sorted shouldBe ordered

    val complexOrdered = Never :: Never :: ttl1 :: ttl2 :: ttl3 :: Forever :: Forever :: Nil

    complexOrdered.sorted shouldBe complexOrdered

    val complexUnordered = ttl3 :: Forever :: ttl1 :: Never :: ttl2 :: Never :: Forever :: Nil

    complexUnordered.sorted shouldBe complexOrdered
  }
}
