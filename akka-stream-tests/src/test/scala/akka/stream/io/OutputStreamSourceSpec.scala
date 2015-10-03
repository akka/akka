/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.{ IOException, OutputStream }
import java.util.concurrent.{ BlockingQueue, TimeoutException }

import akka.actor.{ Deploy, ActorSystem, Props }
import akka.stream._
import akka.stream.actor.ActorPublisher.Internal.Subscribe
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.io.{ OutputStreamPublisher, OutputStreamSource }
import akka.stream.impl.{ ActorMaterializerImpl, StreamSupervisor }
import akka.stream.scaladsl.{ Keep, Source }
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.ByteString

import scala.concurrent.duration.Duration.Zero
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class OutputStreamSourceSpec extends AkkaSpec(UnboundedMailboxConfig) {
  import system.dispatcher

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val timeout = 300.milliseconds
  val bytes = "abc".getBytes
  val byteString = ByteString("abc")

  def expectBlocked[T](f: Future[T]) =
    the[Exception] thrownBy Await.result(f, timeout) shouldBe a[TimeoutException]

  def expectSuccess[T](f: Future[T], value: T) =
    Await.result(f, timeout) should be(value)

  def testSource(probe: TestProbe): Source[ByteString, (OutputStream, Future[Long])] = {
    class TestOutputStreamSource(override val timeout: FiniteDuration,
                                 override val attributes: Attributes,
                                 shape: SourceShape[ByteString])
      extends OutputStreamSource(timeout, attributes, shape) {
      override def getPublisher(buffer: BlockingQueue[ByteString], p: Promise[Long]) =
        Props(new TestOutputStreamPublisher(buffer, p)).withDeploy(Deploy.local)
    }

    class TestOutputStreamPublisher(buffer: BlockingQueue[ByteString], bytesReadPromise: Promise[Long])
      extends OutputStreamPublisher(buffer, bytesReadPromise) {
      protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit = {
        super.aroundReceive(receive, msg)
        probe.ref ! msg
      }
    }
    new Source(new TestOutputStreamSource(timeout, DefaultAttributes.outputStreamSource, Source.shape("TestOutputStreamSource")))
  }

  "OutputStreamSource" must {
    "read bytes from OutputStream" in assertAllStagesStopped {
      val ((outputStream, f), probe) = OutputStreamSource().toMat(TestSink.probe[ByteString])(Keep.both).run
      val s = probe.expectSubscription()

      outputStream.write(bytes)
      s.request(1)
      probe.expectNext(byteString)
      outputStream.close()
      probe.expectComplete()
      Await.result(f, timeout) should be(3)
    }

    "block flush call until send all buffer to downstream" in assertAllStagesStopped {
      val ((outputStream, _), probe) = OutputStreamSource().toMat(TestSink.probe[ByteString])(Keep.both).run
      val s = probe.expectSubscription()

      outputStream.write(bytes)
      val f = Future(outputStream.flush())

      expectBlocked(f)
      probe.expectNoMsg(Zero)

      s.request(1)
      expectSuccess(f, ())
      probe.expectNext(byteString)

      outputStream.close()
      probe.expectComplete()
    }

    "not block flushes when buffer is empty" in assertAllStagesStopped {
      val ((outputStream, _), probe) = OutputStreamSource().toMat(TestSink.probe[ByteString])(Keep.both).run
      val s = probe.expectSubscription()

      outputStream.write(bytes)

      val f = Future(outputStream.flush())
      s.request(1)
      expectSuccess(f, ())
      probe.expectNext(byteString)

      val f2 = Future(outputStream.flush())
      expectSuccess(f2, ())

      outputStream.close()
      probe.expectComplete()
    }

    "block writes when buffer is full" in assertAllStagesStopped {
      val ((outputStream, _), probe) = OutputStreamSource().toMat(TestSink.probe[ByteString])(Keep.both).run
      val s = probe.expectSubscription()

      //default max-input-buffer-size = 16
      (1 to 15).foreach { _ ⇒ outputStream.write(bytes) }

      //blocked call
      val f = Future(outputStream.write(bytes))

      expectBlocked(f)
      probe.expectNoMsg(Zero)

      s.request(16)
      expectSuccess(f, ())
      probe.expectNextN(List.fill(16)(byteString).toSeq)

      outputStream.close()
      probe.expectComplete()
    }

    "throw error when write after stream is closed" in assertAllStagesStopped {
      val ((outputStream, _), probe) = OutputStreamSource().toMat(TestSink.probe[ByteString])(Keep.both).run

      probe.expectSubscription()
      outputStream.close()
      probe.expectComplete()
      the[Exception] thrownBy outputStream.write(bytes) shouldBe a[IOException]
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val mat = ActorMaterializer()(sys)

      try {
        OutputStreamSource().runWith(TestSink.probe[ByteString])(mat)
        mat.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "outputStream").get
        assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
      } finally shutdown(sys)
    }

    "throw IOException when writing to the stream after the subscriber has cancelled the reactive stream" in assertAllStagesStopped {
      val sourceProbe = TestProbe()
      val ((outputStream, _), probe) = testSource(sourceProbe).toMat(TestSink.probe[ByteString])(Keep.both).run

      val s = probe.expectSubscription()
      sourceProbe.expectMsgClass(classOf[Subscribe])

      outputStream.write(bytes)
      sourceProbe.expectMsg(OutputStreamPublisher.WriteNotification)
      s.request(1)

      sourceProbe.expectMsgClass(classOf[Request])
      probe.expectNext(byteString)

      s.cancel()
      sourceProbe.expectMsg(Cancel)
      the[Exception] thrownBy outputStream.write(bytes) shouldBe a[IOException]
    }
  }
}