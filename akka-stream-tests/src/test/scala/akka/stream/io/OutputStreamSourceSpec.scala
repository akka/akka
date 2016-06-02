/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.io

import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeoutException
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.Attributes.inputBuffer
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.io.OutputStreamSourceStage
import akka.stream.impl.{ ActorMaterializerImpl, StreamSupervisor }
import akka.stream.scaladsl.{ Source, Keep, StreamConverters, Sink }
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.Duration.Zero
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Random
import akka.testkit.AkkaSpec

class OutputStreamSourceSpec extends AkkaSpec(UnboundedMailboxConfig) {
  import system.dispatcher

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val timeout = 3.seconds
  val bytesArray = Array.fill[Byte](3)(Random.nextInt(1024).asInstanceOf[Byte])
  val byteString = ByteString(bytesArray)

  def expectTimeout[T](f: Future[T], timeout: Duration) =
    the[Exception] thrownBy Await.result(f, timeout) shouldBe a[TimeoutException]

  def expectSuccess[T](f: Future[T], value: T) =
    Await.result(f, remainingOrDefault) should be(value)

  def assertNoBlockedThreads(): Unit = {
    def threadsBlocked =
      ManagementFactory.getThreadMXBean.dumpAllThreads(true, true).toSeq
        .filter(t ⇒ t.getThreadName.startsWith("OutputStreamSourceSpec") &&
          t.getLockName != null &&
          t.getLockName.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer") &&
          t.getStackTrace.exists(s ⇒ s.getClassName.startsWith(classOf[OutputStreamSourceStage].getName)))

    awaitAssert(threadsBlocked should ===(Seq()), 5.seconds, interval = 500.millis)
  }

  "OutputStreamSource" must {
    "read bytes from OutputStream" in assertAllStagesStopped {
      val (outputStream, probe) = StreamConverters.asOutputStream().toMat(TestSink.probe[ByteString])(Keep.both).run
      val s = probe.expectSubscription()

      outputStream.write(bytesArray)
      s.request(1)
      probe.expectNext(byteString)
      outputStream.close()
      probe.expectComplete()
    }

    "block flush call until send all buffer to downstream" in assertAllStagesStopped {
      val (outputStream, probe) = StreamConverters.asOutputStream().toMat(TestSink.probe[ByteString])(Keep.both).run
      val s = probe.expectSubscription()

      outputStream.write(bytesArray)
      val f = Future(outputStream.flush())

      expectTimeout(f, timeout)
      probe.expectNoMsg(Zero)

      s.request(1)
      expectSuccess(f, ())
      probe.expectNext(byteString)

      outputStream.close()
      probe.expectComplete()
    }

    "not block flushes when buffer is empty" in assertAllStagesStopped {
      val (outputStream, probe) = StreamConverters.asOutputStream().toMat(TestSink.probe[ByteString])(Keep.both).run
      val s = probe.expectSubscription()

      outputStream.write(bytesArray)

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
      val (outputStream, probe) = StreamConverters.asOutputStream().toMat(TestSink.probe[ByteString])(Keep.both)
        .withAttributes(Attributes.inputBuffer(16, 16)).run
      val s = probe.expectSubscription()

      (1 to 16).foreach { _ ⇒ outputStream.write(bytesArray) }

      //blocked call
      val f = Future(outputStream.write(bytesArray))

      expectTimeout(f, timeout)
      probe.expectNoMsg(Zero)

      s.request(17)
      expectSuccess(f, ())
      probe.expectNextN(List.fill(17)(byteString).toSeq)

      outputStream.close()
      probe.expectComplete()
    }

    "throw error when write after stream is closed" in assertAllStagesStopped {
      val (outputStream, probe) = StreamConverters.asOutputStream().toMat(TestSink.probe[ByteString])(Keep.both).run

      probe.expectSubscription()
      outputStream.close()
      probe.expectComplete()
      the[Exception] thrownBy outputStream.write(bytesArray) shouldBe a[IOException]
    }

    "use dedicated default-blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)

      try {
        StreamConverters.asOutputStream().runWith(TestSink.probe[ByteString])(materializer)
        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "outputStreamSource").get
        assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
      } finally shutdown(sys)

    }

    "throw IOException when writing to the stream after the subscriber has cancelled the reactive stream" in assertAllStagesStopped {
      val sourceProbe = TestProbe()
      val (outputStream, probe) = TestSourceStage(new OutputStreamSourceStage(timeout), sourceProbe)
        .toMat(TestSink.probe[ByteString])(Keep.both).run

      val s = probe.expectSubscription()

      outputStream.write(bytesArray)
      s.request(1)
      sourceProbe.expectMsg(GraphStageMessages.Pull)

      probe.expectNext(byteString)

      s.cancel()
      sourceProbe.expectMsg(GraphStageMessages.DownstreamFinish)
      the[Exception] thrownBy outputStream.write(bytesArray) shouldBe a[IOException]
    }

    "fail to materialize with zero sized input buffer" in {
      an[IllegalArgumentException] shouldBe thrownBy {
        StreamConverters.asOutputStream(timeout)
          .withAttributes(inputBuffer(0, 0))
          .runWith(Sink.head)
        /*
             With Sink.head we test the code path in which the source
             itself throws an exception when being materialized. If
             Sink.ignore is used, the same exception is thrown by
             Materializer.
             */
      }
    }

    "not leave blocked threads" in {
      // make sure previous tests didn't leak
      assertNoBlockedThreads()

      val (outputStream, probe) = StreamConverters.asOutputStream(timeout)
        .toMat(TestSink.probe[ByteString])(Keep.both).run()(materializer)

      val sub = probe.expectSubscription()

      // triggers a blocking read on the queue
      // and then cancel the stage before we got anything
      sub.request(1)
      sub.cancel()

      assertNoBlockedThreads()
    }

    "not leave blocked threads when materializer shutdown" in {
      val materializer2 = ActorMaterializer(settings)
      val (outputStream, probe) = StreamConverters.asOutputStream(timeout)
        .toMat(TestSink.probe[ByteString])(Keep.both).run()(materializer2)

      val sub = probe.expectSubscription()

      // triggers a blocking read on the queue
      // and then shutdown the materializer before we got anything
      sub.request(1)
      materializer2.shutdown()

      assertNoBlockedThreads()
    }
  }
}
