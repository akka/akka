/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.InputStream
import java.util.concurrent.CountDownLatch

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.StreamConverters
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

class InputStreamSourceSpec extends AkkaSpec(UnboundedMailboxConfig) with ScalaFutures {

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  "InputStreamSource" must {
    "read bytes from InputStream" in assertAllStagesStopped {
      val f = StreamConverters.fromInputStream(() ⇒ new InputStream {
        @volatile var buf = List("a", "b", "c").map(_.charAt(0).toInt)
        override def read(): Int = {
          buf match {
            case head :: tail ⇒
              buf = tail
              head
            case Nil ⇒
              -1
          }

        }
      })
        .runWith(Sink.head)

      f.futureValue should ===(ByteString("abc"))
    }

    "emit as soon as read" in assertAllStagesStopped {
      val latch = new CountDownLatch(1)
      val probe = StreamConverters.fromInputStream(() ⇒ new InputStream {
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
      }, chunkSize = 1)
        .runWith(TestSink.probe)

      probe.request(4)
      probe.expectNext(ByteString("M"))
      latch.countDown()
      probe.expectComplete()
    }
  }

}
