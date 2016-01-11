/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.{ IOException, InputStream }
import java.util.concurrent.TimeoutException

import akka.actor.{ ActorSystem, NoSerializationVerificationNeeded }
import akka.stream._
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.io.InputStreamSinkStage
import akka.stream.impl.{ ActorMaterializerImpl, StreamSupervisor }
import akka.stream.scaladsl.{ Source, Keep, Sink, StreamConverters }
import akka.stream.stage.InHandler
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.TestProbe
import akka.util.ByteString
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.{ Await, Future }
import scala.util.control.NoStackTrace

class InputStreamSinkSpec extends AkkaSpec(UnboundedMailboxConfig) {
  import system.dispatcher

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val timeout = 300.milliseconds
  def randomByteString(size: Int): ByteString = {
    val a = new Array[Byte](size)
    ThreadLocalRandom.current().nextBytes(a)
    ByteString(a)
  }

  val byteString = randomByteString(3)
  val byteArray = byteString.toArray

  private[this] def readN(is: InputStream, n: Int): (Int, ByteString) = {
    val buf = new Array[Byte](n)
    val r = is.read(buf)
    (r, ByteString.fromArray(buf, 0, r))
  }

  object InputStreamSinkTestMessages {
    case object Push extends NoSerializationVerificationNeeded
    case object Finish extends NoSerializationVerificationNeeded
    case class Failure(ex: Throwable) extends NoSerializationVerificationNeeded
  }

  def testSink(probe: TestProbe): Sink[ByteString, InputStream] = {
    class InputStreamSinkTestStage(val timeout: FiniteDuration)
      extends InputStreamSinkStage(timeout) {

      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
        val (logic, inputStream) = super.createLogicAndMaterializedValue(inheritedAttributes)
        val inHandler = logic.handlers(in.id).asInstanceOf[InHandler]
        logic.handlers(in.id) = new InHandler {
          override def onPush(): Unit = {
            probe.ref ! InputStreamSinkTestMessages.Push
            inHandler.onPush()
          }
          override def onUpstreamFinish(): Unit = {
            probe.ref ! InputStreamSinkTestMessages.Finish
            inHandler.onUpstreamFinish()
          }
          override def onUpstreamFailure(ex: Throwable): Unit = {
            probe.ref ! InputStreamSinkTestMessages.Failure(ex)
            inHandler.onUpstreamFailure(ex)
          }
        }
        (logic, inputStream)
      }
    }
    Sink.fromGraph(new InputStreamSinkTestStage(timeout))
  }

  "InputStreamSink" must {
    "read bytes from InputStream" in assertAllStagesStopped {
      val inputStream = Source.single(byteString).runWith(StreamConverters.asInputStream())
      readN(inputStream, byteString.size) should ===((byteString.size, byteString))
      inputStream.close()
    }

    "read bytes correctly if requested by InputStream not in chunk size" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val byteString2 = randomByteString(3)
      val inputStream = Source(byteString :: byteString2 :: Nil).runWith(testSink(sinkProbe))

      sinkProbe.expectMsgAllOf(InputStreamSinkTestMessages.Push, InputStreamSinkTestMessages.Push)

      readN(inputStream, 2) should ===((2, byteString.take(2)))
      readN(inputStream, 2) should ===((2, byteString.drop(2) ++ byteString2.take(1)))
      readN(inputStream, 2) should ===((2, byteString2.drop(1)))

      inputStream.close()
    }

    "returns less than was expected when the data source has provided some but not enough data" in assertAllStagesStopped {
      val inputStream = Source.single(byteString).runWith(StreamConverters.asInputStream())

      val arr = new Array[Byte](byteString.size + 1)
      inputStream.read(arr) should ===(arr.size - 1)
      ByteString(arr) should ===(byteString :+ 0)

      inputStream.close()
    }

    "block read until get requested number of bytes from upstream" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(StreamConverters.asInputStream())(Keep.both).run()
      val f = Future(inputStream.read(new Array[Byte](byteString.size)))

      the[Exception] thrownBy Await.result(f, timeout) shouldBe a[TimeoutException]
      probe.sendNext(byteString)
      Await.result(f, timeout) should ===(byteString.size)

      probe.sendComplete()
      inputStream.read() should ===(-1)
      inputStream.close()
    }

    "fill up buffer by default" in assertAllStagesStopped {
      val byteString2 = randomByteString(3)
      val inputStream = Source(byteString :: byteString2 :: Nil).runWith(StreamConverters.asInputStream())

      readN(inputStream, 3) should ===((3, byteString))
      readN(inputStream, 3) should ===((3, byteString2))

      inputStream.close()
    }

    "throw error when reactive stream is closed" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(StreamConverters.asInputStream())(Keep.both).run()
      probe.sendNext(byteString)
      inputStream.close()
      probe.expectCancellation()
      the[Exception] thrownBy inputStream.read() shouldBe a[IOException]
    }

    "return all data when upstream is completed" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(testSink(sinkProbe))(Keep.both).run()
      val bytes = randomByteString(1)

      probe.sendNext(bytes)
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Push)

      probe.sendComplete()
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Finish)

      readN(inputStream, 3) should ===((1, bytes))
    }

    "work when read chunks smaller than stream chunks" in assertAllStagesStopped {
      val bytes = randomByteString(10)
      val inputStream = Source.single(bytes).runWith(StreamConverters.asInputStream())

      for (expect ← bytes.sliding(3, 3))
        readN(inputStream, 3) should ===((expect.size, expect))

      inputStream.close()
    }

    "throw exception when call read with wrong parameters" in assertAllStagesStopped {
      val inputStream = Source.single(byteString).runWith(StreamConverters.asInputStream())
      val buf = new Array[Byte](3)
      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(buf, -1, 2))
      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(buf, 0, 5))
      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(new Array[Byte](0), 0, 1))
      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(buf, 0, 0))
      inputStream.close()
    }

    "successfully read several chunks at once" in assertAllStagesStopped {
      val bytes = List.fill(4)(randomByteString(4))
      val sinkProbe = TestProbe()
      val inputStream = Source[ByteString](bytes).runWith(testSink(sinkProbe))

      //need to wait while all elements arrive to sink
      bytes foreach { _ ⇒ sinkProbe.expectMsg(InputStreamSinkTestMessages.Push) }

      for (i ← 0 to 1)
        readN(inputStream, 8) should ===((8, bytes(i * 2) ++ bytes(i * 2 + 1)))

      inputStream.close()
    }

    "work when read chunks bigger than stream chunks" in assertAllStagesStopped {
      val bytes1 = randomByteString(10)
      val bytes2 = randomByteString(10)
      val sinkProbe = TestProbe()
      val inputStream = Source(bytes1 :: bytes2 :: Nil).runWith(testSink(sinkProbe))

      //need to wait while both elements arrive to sink
      sinkProbe.expectMsgAllOf(InputStreamSinkTestMessages.Push, InputStreamSinkTestMessages.Push)

      readN(inputStream, 15) should ===((15, bytes1 ++ bytes2.take(5)))
      readN(inputStream, 15) should ===((5, bytes2.drop(5)))

      inputStream.close()
    }

    "return -1 when read after stream is completed" in assertAllStagesStopped {
      val inputStream = Source.single(byteString).runWith(StreamConverters.asInputStream())

      readN(inputStream, byteString.size) should ===((byteString.size, byteString))
      inputStream.read() should ===(-1)

      inputStream.close()
    }

    "return IOException when stream is failed" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(testSink(sinkProbe))(Keep.both).run()
      val ex = new RuntimeException("Stream failed.") with NoStackTrace

      probe.sendNext(byteString)
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Push)

      readN(inputStream, byteString.size) should ===((byteString.size, byteString))

      probe.sendError(ex)
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Failure(ex))
      val e = intercept[IOException] { Await.result(Future(inputStream.read()), timeout) }
      e.getCause should ===(ex)
    }

    "use dedicated default-blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      try {
        TestSource.probe[ByteString].runWith(StreamConverters.asInputStream())(materializer)
        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "inputStreamSink").get
        assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
      } finally shutdown(sys)
    }
  }
}
