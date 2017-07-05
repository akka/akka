/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.testkit.{ StreamSpec, TestSubscriber, Utils }
import akka.testkit.DefaultTimeout
import org.scalatest.time.{ Millis, Span }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.control.NoStackTrace

class FailedSourceSpec extends StreamSpec with DefaultTimeout {

  implicit val materializer = ActorMaterializer()

  "The Failed Source" must {
    "emit error immediately" in {
      val ex = new RuntimeException with NoStackTrace
      val p = Source.failed(ex).runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      c.expectSubscriptionAndError(ex)

      // reject additional subscriber
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c2)
      c2.expectSubscriptionAndError()
    }
  }

}