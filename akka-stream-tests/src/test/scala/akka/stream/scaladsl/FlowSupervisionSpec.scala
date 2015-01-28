/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.duration._
import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit.AkkaSpec
import scala.util.control.NoStackTrace
import scala.concurrent.Await
import akka.stream.testkit.StreamTestKit.SubscriberProbe
import akka.stream.Supervision

class FlowSupervisionSpec extends AkkaSpec {
  import OperationAttributes.supervisionStrategy

  implicit val materializer = ActorFlowMaterializer()(system)

  val exc = new RuntimeException("simulated exc") with NoStackTrace

  val failingMap = Flow[Int].map(n ⇒ if (n == 3) throw exc else n)

  def run(f: Flow[Int, Int, Unit]): immutable.Seq[Int] =
    Await.result(Source(1 to 5).via(f).grouped(1000).runWith(Sink.head()), 3.seconds)

  "Stream supervision" must {

    "stop and complete stream with failure by default" in {
      intercept[RuntimeException] {
        run(failingMap)
      } should be(exc)
    }

    "support resume " in {
      val result = run(failingMap.withAttributes(supervisionStrategy(Supervision.resumingDecider)))
      result should be(List(1, 2, 4, 5))
    }

  }
}
