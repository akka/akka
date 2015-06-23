/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.testkit.{ AkkaSpec, TestSubscriber }
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }

import akka.stream.Supervision._
import akka.stream.ActorOperationAttributes._
import scala.util.control.NoStackTrace
import scala.concurrent.duration._

class FlowRecoverSpec extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system).withInputBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer = ActorFlowMaterializer(settings)

  val ex = new RuntimeException("ex") with NoStackTrace

  "A Recover" must {
    "recover when there is a handler" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()

      Source(1 to 4).map { a ⇒ if (a == 3) throw ex else a }
        .recover { case t: Throwable ⇒ 0 }
        .runWith(Sink(subscriber))

      subscriber.requestNext(1)
      subscriber.requestNext(2)

      subscriber.request(1)
      subscriber.expectNext(0)

      subscriber.request(1)
      subscriber.expectComplete()
    }

    "failed stream if handler is not for such exception type" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()

      Source(1 to 3).map { a ⇒ if (a == 2) throw ex else a }
        .recover { case t: IndexOutOfBoundsException ⇒ 0 }
        .runWith(Sink(subscriber))

      subscriber.requestNext(1)
      subscriber.request(1)
      subscriber.expectError(ex)
    }

    "not influence stream when there is no exceptions" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()

      val k = Source(1 to 3).map(identity)
        .recover { case t: Throwable ⇒ 0 }
        .runWith(Sink(subscriber))

      subscriber.requestNext(1)
      subscriber.requestNext(2)
      subscriber.requestNext(3)
      subscriber.expectComplete()
    }

    "finish stream if it's empty" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()
      Source.empty.map(identity)
        .recover { case t: Throwable ⇒ 0 }
        .runWith(Sink(subscriber))

      subscriber.request(1)
      subscriber.expectComplete()

    }
  }
}
