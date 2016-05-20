/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.Utils._
import akka.testkit.AkkaSpec

import scala.util.control.NoStackTrace

class FlowRecoverWithSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer = ActorMaterializer(settings)

  val ex = new RuntimeException("ex") with NoStackTrace

  "A RecoverWith" must {
    "recover when there is a handler" in assertAllStagesStopped {
      Source(1 to 4).map { a ⇒ if (a == 3) throw ex else a }
        .recoverWith { case t: Throwable ⇒ Source(List(0, -1)) }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(1 to 2)
        .request(1)
        .expectNext(0)
        .request(1)
        .expectNext(-1)
        .expectComplete()
    }

    "cancel substream if parent is terminated when there is a handler" in assertAllStagesStopped {
      Source(1 to 4).map { a ⇒ if (a == 3) throw ex else a }
        .recoverWith { case t: Throwable ⇒ Source(List(0, -1)) }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(1 to 2)
        .request(1)
        .expectNext(0)
        .cancel()
    }

    "failed stream if handler is not for such exception type" in assertAllStagesStopped {
      Source(1 to 3).map { a ⇒ if (a == 2) throw ex else a }
        .recoverWith { case t: IndexOutOfBoundsException ⇒ Source.single(0) }
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(1)
        .request(1)
        .expectError(ex)
    }

    "be able to recover with the same unmaterialized source if configured" in assertAllStagesStopped {
      val src = Source(1 to 3).map { a ⇒ if (a == 3) throw ex else a }
      src.recoverWith { case t: Throwable ⇒ src }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(1 to 2)
        .request(2)
        .expectNextN(1 to 2)
        .request(2)
        .expectNextN(1 to 2)
        .cancel()
    }

    "not influence stream when there is no exceptions" in assertAllStagesStopped {
      Source(1 to 3).map(identity)
        .recoverWith { case t: Throwable ⇒ Source.single(0) }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNextN(1 to 3)
        .expectComplete()
    }

    "finish stream if it's empty" in assertAllStagesStopped {
      Source.empty.map(identity)
        .recoverWith { case t: Throwable ⇒ Source.single(0) }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectComplete()
    }

    "switch the second time if alternative source throws exception" in assertAllStagesStopped {
      val k = Source(1 to 3).map { a ⇒ if (a == 3) throw new IndexOutOfBoundsException() else a }
        .recoverWith {
          case t: IndexOutOfBoundsException ⇒
            Source(List(11, 22)).map(m ⇒ if (m == 22) throw new IllegalArgumentException() else m)
          case t: IllegalArgumentException ⇒ Source(List(33, 44))
        }.runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(List(1, 2))
        .request(2)
        .expectNextN(List(11, 33))
        .request(1)
        .expectNext(44)
        .expectComplete()
    }

    "terminate with exception if partial function fails to match after an alternative source failure" in assertAllStagesStopped {
      Source(1 to 3).map { a ⇒ if (a == 3) throw new IndexOutOfBoundsException() else a }
        .recoverWith {
          case t: IndexOutOfBoundsException ⇒
            Source(List(11, 22)).map(m ⇒ if (m == 22) throw ex else m)
        }.runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(List(1, 2))
        .request(1)
        .expectNextN(List(11))
        .request(1)
        .expectError(ex)
    }

    "terminate with exception after set number of retries" in assertAllStagesStopped {
      Source(1 to 3).map { a ⇒ if (a == 3) throw new IndexOutOfBoundsException() else a }
        .recoverWithRetries(3, {
          case t: Throwable ⇒
            Source(List(11, 22)).concat(Source.failed(ex))
        }).runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(List(1, 2))
        .request(2)
        .expectNextN(List(11, 22))
        .request(2)
        .expectNextN(List(11, 22))
        .request(2)
        .expectNextN(List(11, 22))
        .request(1)
        .expectError(ex)
    }

    "throw IllegalArgumentException if number of retries is less than -1" in assertAllStagesStopped {
      intercept[IllegalArgumentException] {
        Flow[Int].recoverWithRetries(-2, { case t: Throwable ⇒ Source.empty[Int] })
      }
    }
  }
}
