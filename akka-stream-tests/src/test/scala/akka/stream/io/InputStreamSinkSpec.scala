/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.io

import java.io.{ IOException, InputStream }
import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.Attributes.inputBuffer
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.io.InputStreamSinkStage
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.stream.scaladsl.{ Keep, Source, StreamConverters }
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.TestSource
import akka.stream.testkit._
import akka.testkit.TestProbe
import akka.util.ByteString

import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.{ Await, Future }
import scala.util.control.NoStackTrace

class InputStreamSinkSpec extends StreamSpec(UnboundedMailboxConfig) {
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

  def readN(is: InputStream, n: Int): (Int, ByteString) = {
    val buf = new Array[Byte](n)
    val r = is.read(buf)
    (r, ByteString.fromArray(buf, 0, r))
  }
  def testSink(probe: TestProbe) = TestSinkStage(new InputStreamSinkStage(timeout), probe)

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

      sinkProbe.expectMsgAllOf(GraphStageMessages.Push, GraphStageMessages.Push)

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
      Await.result(f, remainingOrDefault) should ===(byteString.size)

      probe.sendComplete()
      inputStream.read() should ===(-1)
      inputStream.close()
    }

    "ignore an empty ByteString" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(StreamConverters.asInputStream())(Keep.both).run()
      probe.sendNext(ByteString.empty)
      val f = Future(inputStream.read())

      the[Exception] thrownBy Await.result(f, timeout) shouldBe a[TimeoutException]
      probe.sendComplete()
      Await.result(f, remainingOrDefault) should ===(-1)
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
      sinkProbe.expectMsg(GraphStageMessages.Push)

      probe.sendComplete()
      sinkProbe.expectMsg(GraphStageMessages.UpstreamFinish)

      readN(inputStream, 3) should ===((1, bytes))
    }

    "work when read chunks smaller than stream chunks" in assertAllStagesStopped {
      val bytes = randomByteString(10)
      val inputStream = Source.single(bytes).runWith(StreamConverters.asInputStream())

      for (expect <- bytes.sliding(3, 3))
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
      bytes.foreach { _ =>
        sinkProbe.expectMsg(GraphStageMessages.Push)
      }

      for (i <- 0 to 1)
        readN(inputStream, 8) should ===((8, bytes(i * 2) ++ bytes(i * 2 + 1)))

      inputStream.close()
    }

    "work when read chunks bigger than stream chunks" in assertAllStagesStopped {
      val bytes1 = randomByteString(10)
      val bytes2 = randomByteString(10)
      val sinkProbe = TestProbe()
      val inputStream = Source(bytes1 :: bytes2 :: Nil).runWith(testSink(sinkProbe))

      //need to wait while both elements arrive to sink
      sinkProbe.expectMsgAllOf(GraphStageMessages.Push, GraphStageMessages.Push)

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
      sinkProbe.expectMsg(GraphStageMessages.Push)

      readN(inputStream, byteString.size) should ===((byteString.size, byteString))

      probe.sendError(ex)
      sinkProbe.expectMsg(GraphStageMessages.Failure(ex))
      val e = intercept[IOException] { Await.result(Future(inputStream.read()), timeout) }
      e.getCause should ===(ex)
    }

    "use dedicated default-blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      try {
        TestSource.probe[ByteString].runWith(StreamConverters.asInputStream())(materializer)
        materializer
          .asInstanceOf[PhasedFusingActorMaterializer]
          .supervisor
          .tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "inputStreamSink").get
        assertDispatcher(ref, ActorAttributes.IODispatcher.dispatcher)
      } finally shutdown(sys)
    }

    "work when more bytes pulled from InputStream than available" in assertAllStagesStopped {
      val inputStream = Source.single(byteString).runWith(StreamConverters.asInputStream())

      readN(inputStream, byteString.size * 2) should ===((byteString.size, byteString))
      inputStream.read() should ===(-1)

      inputStream.close()
    }

    "read next byte as an int from InputStream" in assertAllStagesStopped {
      val bytes = ByteString(0, 100, 200, 255)
      val inputStream = Source.single(bytes).runWith(StreamConverters.asInputStream())
      List.fill(5)(inputStream.read()) should ===(List(0, 100, 200, 255, -1))
      inputStream.close()
    }

    "fail to materialize with zero sized input buffer" in {
      an[IllegalArgumentException] shouldBe thrownBy {
        Source.single(byteString).runWith(StreamConverters.asInputStream(timeout).withAttributes(inputBuffer(0, 0)))
        /*
         With Source.single we test the code path in which the sink
         itself throws an exception when being materialized. If
         Source.empty is used, the same exception is thrown by
         Materializer.
       */
      }
    }

    "throw from inputstream read if terminated abruptly" in {
      val mat = ActorMaterializer()
      val probe = TestPublisher.probe[ByteString]()
      val inputStream = Source.fromPublisher(probe).runWith(StreamConverters.asInputStream())(mat)
      mat.shutdown()

      intercept[IOException] {
        inputStream.read()
      }
    }

    "propagate error to InputStream" in {
      val readTimeout = 3.seconds
      val (probe, inputStream) =
        TestSource.probe[ByteString].toMat(StreamConverters.asInputStream(readTimeout))(Keep.both).run()

      probe.sendNext(ByteString("one"))
      val error = new RuntimeException("failure")
      probe.sendError(error)
      val buffer = Array.ofDim[Byte](5)
      val thrown = intercept[IOException] {
        inputStream.read(buffer) should !==(-1)
      }
      thrown.getCause should ===(error)
    }
  }

}
