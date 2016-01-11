/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.{ IOException, OutputStream }
import java.util.concurrent.TimeoutException

import akka.actor.{ ActorSystem, NoSerializationVerificationNeeded }
import akka.stream._
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.io.OutputStreamSourceStage
import akka.stream.impl.{ ActorMaterializerImpl, StreamSupervisor }
import akka.stream.scaladsl.{ Keep, Source, StreamConverters }
import akka.stream.stage.OutHandler
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.ByteString

import scala.concurrent.duration.Duration.Zero
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Random

class OutputStreamSourceSpec extends AkkaSpec(UnboundedMailboxConfig) {
  import system.dispatcher

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val timeout = 300.milliseconds
  val bytesArray = Array.fill[Byte](3)(Random.nextInt(1024).asInstanceOf[Byte])
  val byteString = ByteString(bytesArray)

  def expectTimeout[T](f: Future[T], timeout: Duration) =
    the[Exception] thrownBy Await.result(f, timeout) shouldBe a[TimeoutException]

  def expectSuccess[T](f: Future[T], value: T) =
    Await.result(f, timeout) should be(value)

  object OutputStreamSourceTestMessages {
    case object Pull extends NoSerializationVerificationNeeded
    case object Finish extends NoSerializationVerificationNeeded
  }

  def testSource(probe: TestProbe): Source[ByteString, OutputStream] = {
    class OutputStreamSourceTestStage(val timeout: FiniteDuration)
      extends OutputStreamSourceStage(timeout) {

      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
        val (logic, inputStream) = super.createLogicAndMaterializedValue(inheritedAttributes)
        val outHandler = logic.handlers(out.id).asInstanceOf[OutHandler]
        logic.handlers(out.id) = new OutHandler {
          override def onDownstreamFinish(): Unit = {
            probe.ref ! OutputStreamSourceTestMessages.Finish
            outHandler.onDownstreamFinish()
          }
          override def onPull(): Unit = {
            probe.ref ! OutputStreamSourceTestMessages.Pull
            outHandler.onPull()
          }
        }
        (logic, inputStream)
      }
    }
    Source.fromGraph(new OutputStreamSourceTestStage(timeout))
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
      val (outputStream, probe) = testSource(sourceProbe).toMat(TestSink.probe[ByteString])(Keep.both).run

      val s = probe.expectSubscription()

      outputStream.write(bytesArray)
      s.request(1)
      sourceProbe.expectMsg(OutputStreamSourceTestMessages.Pull)

      probe.expectNext(byteString)

      s.cancel()
      sourceProbe.expectMsg(OutputStreamSourceTestMessages.Finish)
      the[Exception] thrownBy outputStream.write(bytesArray) shouldBe a[IOException]
    }
  }
}