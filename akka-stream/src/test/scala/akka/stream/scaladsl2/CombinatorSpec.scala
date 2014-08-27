/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import org.scalatest.Matchers
import org.scalatest.WordSpec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import akka.stream.OverflowStrategy

class CombinatorSpec extends WordSpec with Matchers {
  val f = FlowFrom[Int]

  "Linear simple combinators in Flow" should {
    "map" in {
      val t: ProcessorFlow[Int, Int] = f.map(_ * 2)
    }
  }

}
