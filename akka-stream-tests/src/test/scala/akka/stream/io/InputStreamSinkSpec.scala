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
import akka.stream.scaladsl.{ Keep, Sink }
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
  def randomArray(size: Int) = {
    val a = new Array[Byte](size)
    Random.nextBytes(a)
    a
  }

  val byteArray = randomArray(3)
  val byteString = ByteString(byteArray)

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
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(Sink.inputStream())(Keep.both).run()

      probe.sendNext(byteString)
      val arr = newArray()
      inputStream.read(arr)
      arr should ===(byteArray)

      probe.sendComplete()
      inputStream.close()
    }

    "read bytes correctly if requested by InputStream not in chunk size" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(testSink(sinkProbe))(Keep.both).run()

      probe.sendNext(byteString)
      val byteArray2 = randomArray(3)
      probe.sendNext(ByteString(byteArray2))

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
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(Sink.inputStream())(Keep.both).run()

      val data = randomArray(2)
      probe.sendNext(ByteString(data))
      val arr = newArray()
      inputStream.read(arr) should ===(2)
      arr should ===(Array(data(0), data(1), 0))

      probe.sendComplete()
      inputStream.close()
    }

    "block read until get requested number of bytes from upstream" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(Sink.inputStream())(Keep.both).run()

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
      import system.dispatcher
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(Sink.inputStream())(Keep.both).run()

      val array2 = randomArray(3)
      probe.sendNext(byteString)
      probe.sendNext(ByteString(array2))

      val arr1 = newArray()
      val arr2 = newArray()
      val f1 = Future(inputStream.read(arr1))
      val f2 = Future(inputStream.read(arr2))
      Await.result(f1, timeout) should be(3)
      Await.result(f2, timeout) should be(3)

      arr1 should ===(byteString)
      arr2 should ===(array2)

      probe.sendComplete()
      inputStream.close()
    }

    "throw error when reactive stream is closed" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(Sink.inputStream())(Keep.both).run()

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

    "return -1 when read after stream is completed" in assertAllStagesStopped {
      val (probe, inputStream) = TestSource.probe[ByteString].toMat(Sink.inputStream())(Keep.both).run()

      probe.sendNext(byteString)
      val arr = newArray()
      inputStream.read(arr)
      arr should ===(byteArray)
      probe.sendComplete()

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
        TestSource.probe[ByteString].runWith(Sink.inputStream())(materializer)
        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "inputStreamSink").get
        assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
      } finally shutdown(sys)
    }

  }

}
