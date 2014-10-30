/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ Transformer, FlowMaterializer, MaterializerSettings }
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }

import scala.language.postfixOps

class StageSpec extends AkkaSpec(ConfigFactory.parseString("akka.loglevel=INFO")) {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val mat = FlowMaterializer(settings)

  "A Stage" must {
    "be combinable" in {
      val stage1 = Stage(Flow[Int].map(_ * 3), Flow[Int].map(_ / 3))
      val stage2 = Stage(Flow[Int].map(_.toString), Flow[String].map(_.toInt))

      val flow = stage1.connect(stage2).connect(Flow[String])
      val future = Source(0 to 47).connect(flow).fold(0)(_ + _)

      Await.result(future, 3 seconds) should be((0 to 47).fold(0)(_ + _))
    }
  }
}
