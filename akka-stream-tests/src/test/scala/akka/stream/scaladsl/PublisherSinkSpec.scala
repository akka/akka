/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.FlowMaterializer

import akka.stream.testkit.AkkaSpec
import org.scalatest.concurrent.ScalaFutures._

class PublisherSinkSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "A PublisherSink" must {

    "be unique when created twice" in {
      val p1 = Sink.publisher[Int]
      val p2 = Sink.publisher[Int]

      val m = FlowGraph { implicit b ⇒
        import FlowGraphImplicits._

        val bcast = Broadcast[Int]

        Source(0 to 5) ~> bcast
        bcast ~> Flow[Int].map(_ * 2) ~> p1
        bcast ~> p2
      }.run()

      Seq(p1, p2) map { sink ⇒
        Source(m.get(sink)).map(identity).runFold(0)(_ + _)
      } zip Seq(30, 15) foreach {
        case (future, result) ⇒ whenReady(future)(_ shouldBe result)
      }
    }
  }

}
