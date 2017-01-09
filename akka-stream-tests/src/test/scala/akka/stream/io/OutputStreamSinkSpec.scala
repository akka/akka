/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.io

import java.io.OutputStream

import akka.stream.scaladsl.{ Source, StreamConverters }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.TestProbe
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

class OutputStreamSinkSpec extends StreamSpec(UnboundedMailboxConfig) {

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  "OutputStreamSink" must {
    "write bytes to void OutputStream" in assertAllStagesStopped {
      val p = TestProbe()
      val datas = List(ByteString("a"), ByteString("c"), ByteString("c"))

      val completion = Source(datas)
        .runWith(StreamConverters.fromOutputStream(() ⇒ new OutputStream {
          override def write(i: Int): Unit = ()
          override def write(bytes: Array[Byte]): Unit = p.ref ! ByteString(bytes).utf8String
        }))

      p.expectMsg(datas(0).utf8String)
      p.expectMsg(datas(1).utf8String)
      p.expectMsg(datas(2).utf8String)
      Await.ready(completion, 3.seconds)
    }

    "close underlying stream when error received" in assertAllStagesStopped {
      val p = TestProbe()
      Source.failed(new TE("Boom!"))
        .runWith(StreamConverters.fromOutputStream(() ⇒ new OutputStream {
          override def write(i: Int): Unit = ()
          override def close() = p.ref ! "closed"
        }))

      p.expectMsg("closed")
    }

    "close underlying stream when completion received" in assertAllStagesStopped {
      val p = TestProbe()
      Source.empty
        .runWith(StreamConverters.fromOutputStream(() ⇒ new OutputStream {
          override def write(i: Int): Unit = ()
          override def write(bytes: Array[Byte]): Unit = p.ref ! ByteString(bytes).utf8String
          override def close() = p.ref ! "closed"
        }))

      p.expectMsg("closed")
    }

  }

}
