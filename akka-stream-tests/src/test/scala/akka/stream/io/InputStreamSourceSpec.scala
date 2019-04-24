/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.io

import java.io.InputStream
import java.util.concurrent.CountDownLatch

import akka.Done
import akka.stream.scaladsl.{ Keep, Sink, StreamConverters }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, IOResult }
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class InputStreamSourceSpec extends StreamSpec(UnboundedMailboxConfig) {

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  private def inputStreamFor(bytes: List[Byte]): InputStream =
    new InputStream {
      @volatile var buf = bytes.map(_.toInt)

      override def read(): Int = {
        buf match {
          case head :: tail =>
            buf = tail
            head
          case Nil =>
            -1
        }

      }
    }

  "InputStream Source" must {

    "not signal when no demand" in {
      val f = StreamConverters.fromInputStream(() =>
        new InputStream {
          override def read(): Int = 42
        })

      Await.result(f.takeWithin(1.seconds).runForeach(it => ()), 2.seconds)
    }

    "read bytes from InputStream" in assertAllStagesStopped {
      val f =
        StreamConverters.fromInputStream(() => inputStreamFor(List('a', 'b', 'c').map(_.toByte))).runWith(Sink.head)

      f.futureValue should ===(ByteString("abc"))
    }

    "record number of bytes read" in assertAllStagesStopped {
      StreamConverters
        .fromInputStream(() => inputStreamFor(List(1, 2, 3)))
        .toMat(Sink.ignore)(Keep.left)
        .run
        .futureValue shouldEqual IOResult(3, Success(Done))
    }

    "return IOResult with failure if close fails" in {
      val fail = new RuntimeException("oh dear")
      StreamConverters
        .fromInputStream(() =>
          new InputStream {
            override def read(): Int = -1
            override def close(): Unit = throw fail
          })
        .toMat(Sink.ignore)(Keep.left)
        .run
        .futureValue shouldEqual IOResult(0, Failure(fail))
    }

    "emit as soon as read" in assertAllStagesStopped {
      val latch = new CountDownLatch(1)
      val probe = StreamConverters
        .fromInputStream(
          () =>
            new InputStream {
              @volatile var emitted = false
              override def read(): Int = {
                if (!emitted) {
                  emitted = true
                  'M'.toInt
                } else {
                  latch.await()
                  -1
                }
              }
            },
          chunkSize = 1)
        .runWith(TestSink.probe)

      probe.request(4)
      probe.expectNext(ByteString("M"))
      latch.countDown()
      probe.expectComplete()
    }
  }

}
