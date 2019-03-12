/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.TimeoutException
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.{ StreamSpec, TestSubscriber }
import akka.stream.testkit.scaladsl.StreamTestKit._
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowInitialDelaySpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "Flow initialDelay" must {

    "work with zero delay" in assertAllStagesStopped {
      Await.result(Source(1 to 10).initialDelay(Duration.Zero).grouped(100).runWith(Sink.head), 1.second) should ===(
        1 to 10)
    }

    "delay elements by the specified time but not more" in assertAllStagesStopped {
      a[TimeoutException] shouldBe thrownBy {
        Await.result(Source(1 to 10).initialDelay(2.seconds).initialTimeout(1.second).runWith(Sink.ignore), 2.seconds)
      }

      Await.ready(Source(1 to 10).initialDelay(1.seconds).initialTimeout(2.second).runWith(Sink.ignore), 2.seconds)
    }

    "properly ignore timer while backpressured" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      Source(1 to 10).initialDelay(0.5.second).runWith(Sink.fromSubscriber(probe))

      probe.ensureSubscription()
      probe.expectNoMsg(1.5.second)
      probe.request(20)
      probe.expectNextN(1 to 10)

      probe.expectComplete()
    }

  }

}
