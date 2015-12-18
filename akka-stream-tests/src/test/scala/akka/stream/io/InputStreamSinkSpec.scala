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
import scala.concurrent.{ Await, Future }
import scala.util.Random
import scala.util.control.NoStackTrace

class InputStreamSinkSpec extends AkkaSpec(UnboundedMailboxConfig) {
  import system.dispatcher

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val timeout = 300.milliseconds
  def randomArray(size: Int): Array[Byte] = {
    val a = new Array[Byte](size)
    Random.nextBytes(a)
    a
  }

  val byteArray = randomArray(3)
  val byteString = ByteString(byteArray)

  def newArray(size: Int, bs: ByteString): Array[Byte] = {
    val probe = new Array[Byte](size)
    bs.copyToArray(probe, 0)
    probe
  }
  def newArray() = new Array[Byte](3)

  def expectSuccess[T](f: Future[T], value: T) =
    Await.result(f, timeout) should be(value)

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
      val arr = newArray()
      inputStream.read(arr) should ===(3)
      arr should ===(byteArray)
      inputStream.close()
    }

    "read bytes correctly if requested by InputStream not in chunk size" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val byteArray2 = randomArray(3)
      val inputStream = Source(byteString :: ByteString(byteArray2) :: Nil).runWith(testSink(sinkProbe))

      sinkProbe.expectMsgAllOf(InputStreamSinkTestMessages.Push, InputStreamSinkTestMessages.Push)

      val arr = new Array[Byte](2)
      inputStream.read(arr)
      arr should ===(Array(byteArray(0), byteArray(1)))
      inputStream.read(arr)
      arr should ===(Array(byteArray(2), byteArray2(0)))
      inputStream.read(arr)
      arr should ===(Array(byteArray2(1), byteArray2(2)))

      inputStream.close()
    }

    "returns less than was expected when the data source has provided some but not enough data" in assertAllStagesStopped {
      val data = randomArray(2)
      val inputStream = Source.single(ByteString(data)).runWith(StreamConverters.asInputStream())

      val arr = newArray()
      inputStream.read(arr) should ===(2)
      arr should ===(Array(data(0), data(1), 0))

      inputStream.close()
    }

    "block read until get requested number of bytes from upstream" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(StreamConverters.asInputStream())(Keep.both).run()

      val arr = newArray()
      val f = Future(inputStream.read(arr))
      the[Exception] thrownBy Await.result(f, timeout) shouldBe a[TimeoutException]
      probe.sendNext(byteString)
      expectSuccess(f, 3)

      probe.sendComplete()
      inputStream.read(newArray())
      inputStream.close()
    }

    "fill up buffer by default" in assertAllStagesStopped {
      val array2 = randomArray(3)
      val inputStream = Source(byteString :: ByteString(array2) :: Nil).runWith(StreamConverters.asInputStream())

      val arr1 = newArray()
      val arr2 = newArray()
      val f1 = Future(inputStream.read(arr1))
      val f2 = Future(inputStream.read(arr2))
      Await.result(f1, timeout) should be(3)
      Await.result(f2, timeout) should be(3)

      arr1 should ===(byteString)
      arr2 should ===(array2)

      inputStream.close()
    }

    "throw error when reactive stream is closed" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(StreamConverters.asInputStream())(Keep.both).run()

      probe.sendNext(byteString)
      inputStream.close()
      probe.expectCancellation()
      the[Exception] thrownBy inputStream.read(newArray()) shouldBe a[IOException]
    }

    "return all data when upstream is completed" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(testSink(sinkProbe))(Keep.both).run()

      val bytes = randomArray(1)
      probe.sendNext(ByteString(bytes))
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Push)

      probe.sendComplete()
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Finish)

      val arr = newArray()
      val f = Future(inputStream.read(arr))
      expectSuccess(f, 1)
      arr should ===(Array[Byte](bytes(0), 0, 0))
    }

    "work when read chunks smaller than stream chunks" in assertAllStagesStopped {
      var bytes = ByteString(randomArray(10))
      val inputStream = Source.single(bytes).runWith(StreamConverters.asInputStream())

      for (i ← 0 to 3) {
        val in = newArray()
        inputStream.read(in)
        in should ===(newArray(3, bytes.take(3)))
        bytes = bytes.drop(3)
      }
      inputStream.close()
    }

    "throw exception when call read with wrong parameters" in assertAllStagesStopped {
      val inputStream = Source.single(byteString).runWith(StreamConverters.asInputStream())

      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(newArray(), -1, 2))
      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(newArray(), 0, 5))
      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(new Array[Byte](0), 0, 1))
      an[IllegalArgumentException] shouldBe thrownBy(inputStream.read(newArray(), 0, 0))

      inputStream.close()
    }

    "successfully read several chunks at once" in assertAllStagesStopped {
      val bytes = List.fill(4)(ByteString(randomArray(4)))
      val sinkProbe = TestProbe()
      val inputStream = Source[ByteString](bytes).runWith(testSink(sinkProbe))

      //need to wait while all elements arrive to sink
      for (i ← 0 to 3) sinkProbe.expectMsg(InputStreamSinkTestMessages.Push)

      for (i ← 0 to 1) {
        val in = new Array[Byte](8)
        inputStream.read(in) should ===(8)
        in should ===(bytes(i * 2) ++ bytes(i * 2 + 1))
      }

      inputStream.close()
    }

    "work when read chunks bigger than stream chunks" in assertAllStagesStopped {
      val bytes1 = ByteString(randomArray(10))
      val bytes2 = ByteString(randomArray(10))
      val sinkProbe = TestProbe()

      val inputStream = Source(bytes1 :: bytes2 :: Nil).runWith(testSink(sinkProbe))

      //need to wait while both elements arrive to sink
      sinkProbe.expectMsgAllOf(InputStreamSinkTestMessages.Push, InputStreamSinkTestMessages.Push)

      val in1 = new Array[Byte](15)
      inputStream.read(in1) should ===(15)
      in1 should ===(bytes1 ++ bytes2.take(5))

      val in2 = new Array[Byte](15)
      inputStream.read(in2) should ===(5)
      in2 should ===(newArray(15, bytes2.drop(5)))

      inputStream.close()
    }

    "return -1 when read after stream is completed" in assertAllStagesStopped {
      val inputStream = Source.single(byteString).runWith(StreamConverters.asInputStream())

      val arr = newArray()
      inputStream.read(arr)
      arr should ===(byteArray)

      Await.result(Future(inputStream.read(arr)), timeout) should ===(-1)

      inputStream.close()
    }

    "return IOException when stream is failed" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(testSink(sinkProbe))(Keep.both).run()
      val ex = new RuntimeException("Stream failed.") with NoStackTrace

      probe.sendNext(byteString)
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Push)

      val arr = newArray()
      inputStream.read(arr)

      probe.sendError(ex)
      sinkProbe.expectMsg(InputStreamSinkTestMessages.Failure(ex))
      val p = Future(inputStream.read(arr))
      p.onFailure {
        case e ⇒
          (e.isInstanceOf[IOException] && e.getCause.equals(ex)) should ===(true)
          Unit
      }
      p.onSuccess { case _ ⇒ fail() }

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
