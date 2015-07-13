/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.{ IOException, InputStream }
import java.util.concurrent.TimeoutException

import akka.actor.{ Deploy, ActorSystem, Props }
import akka.stream._
import akka.stream.actor.ActorSubscriber.OnSubscribe
import akka.stream.actor.ActorSubscriberMessage
import akka.stream.actor.ActorSubscriberMessage.{ OnComplete, OnNext, OnError }
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.io.{ InputStreamSink, InputStreamSubscriber }
import akka.stream.impl.{ SinkModule, ActorMaterializerImpl, StreamSupervisor }
import akka.stream.scaladsl.{ Keep, Sink }
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.TestProbe
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.control.NoStackTrace

class InputStreamSinkSpec extends AkkaSpec(UnboundedMailboxConfig) {
  import system.dispatcher

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val timeout = 300.milliseconds
  val byteString = ByteString("abc")
  val byteArray = "abc".toArray
  def newArray() = new Array[Byte](3)

  def expectBlocked[T](f: Future[T]) =
    the[Exception] thrownBy Await.result(f, timeout) shouldBe a[TimeoutException]

  def expectSuccess[T](f: Future[T], value: T) =
    Await.result(f, timeout) should be(value)

  def testSink(probe: TestProbe): Sink[ByteString, (InputStream, Future[Long])] = {
    class TestInputStreamSink(override val timeout: FiniteDuration,
                              override val attributes: Attributes,
                              shape: SinkShape[ByteString])
      extends InputStreamSink(timeout, attributes, shape) {
      override protected def getSubscriber(bytesWrittenPromise: Promise[Long], settings: ActorMaterializerSettings) =
        Props(new TestInputStreamSubscriber(bytesWrittenPromise, settings.maxInputBufferSize)).withDeploy(Deploy.local)

      override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, (InputStream, Future[Long])] =
        new TestInputStreamSink(timeout, attributes, shape)
    }

    class TestInputStreamSubscriber(p: Promise[Long], bufSize: Int)
      extends InputStreamSubscriber(p, bufSize) {
      protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit = {
        super.aroundReceive(receive, msg)
        probe.ref ! msg
      }
    }
    new Sink(new TestInputStreamSink(timeout, DefaultAttributes.inputStreamSink, Sink.shape("TestInputStreamSink")))
  }

  "InputStreamSink" must {
    "read bytes from InputStream" in assertAllStagesStopped {
      val (probe, (inputStream, f)) = TestSource.probe[ByteString].toMat(InputStreamSink())(Keep.both).run()

      probe.sendNext(byteString)
      val arr = newArray()
      inputStream.read(arr)
      assert(arr === byteArray)

      probe.sendComplete()
      inputStream.close()
      expectSuccess(f, 3)
    }

    "returns less than was expected when the data source has provided some but not enough data" in assertAllStagesStopped {
      val (probe, (inputStream, f)) = TestSource.probe[ByteString].toMat(InputStreamSink())(Keep.both).run()

      probe.sendNext(ByteString("ab"))
      val arr = newArray()
      inputStream.read(arr)
      assert(arr === "ab\u0000".toArray)

      probe.sendComplete()
      inputStream.close()
      expectSuccess(f, 2)
    }

    "block read until get requested number of bytes from upstream" in assertAllStagesStopped {
      val (probe, (inputStream, _)) = TestSource.probe[ByteString].toMat(InputStreamSink())(Keep.both).run()

      val arr = newArray()
      val f = Future(inputStream.read(arr))

      expectBlocked(f)
      probe.sendNext(byteString)
      expectSuccess(f, 3)

      probe.sendComplete()
      inputStream.close()
    }

    "fill up buffer by default" in assertAllStagesStopped {
      import system.dispatcher
      val (probe, (inputStream, _)) = TestSource.probe[ByteString].toMat(InputStreamSink())(Keep.both).run()

      probe.sendNext(byteString)
      probe.sendNext(ByteString("def"))

      val arr1 = newArray()
      val arr2 = newArray()
      val f1 = Future(inputStream.read(arr1))
      val f2 = Future(inputStream.read(arr2))
      Await.result(f1, timeout) should be(3)
      Await.result(f2, timeout) should be(3)

      assert(arr1 === byteArray)
      assert(arr2 === "def".toArray)

      probe.sendComplete()
      inputStream.close()
    }

    "throw error when reactive stream is closed" in assertAllStagesStopped {
      val (probe, (inputStream, _)) = TestSource.probe[ByteString].toMat(InputStreamSink())(Keep.both).run()

      probe.sendNext(byteString)

      inputStream.close()
      probe.expectCancellation()
      the[Exception] thrownBy inputStream.read(newArray()) shouldBe a[IOException]
    }

    "return all data when upstream is completed" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val (probe, (inputStream, _)) = TestSource.probe[ByteString].toMat(testSink(sinkProbe))(Keep.both).run()
      sinkProbe.expectMsgClass(classOf[OnSubscribe])

      probe.sendNext(ByteString("a"))
      sinkProbe.expectMsgClass(classOf[OnNext])

      probe.sendComplete()
      sinkProbe.expectMsg(OnComplete)

      val arr = newArray()
      val f = Future(inputStream.read(arr))
      expectSuccess(f, 1)
      assert(arr === "a\u0000\u0000".toArray)
    }

    "return -1 when read after stream is completed" in assertAllStagesStopped {
      val (probe, (inputStream, _)) = TestSource.probe[ByteString].toMat(InputStreamSink())(Keep.both).run()

      probe.sendNext(byteString)
      val arr = newArray()
      inputStream.read(arr)
      assert(arr === byteArray)
      probe.sendComplete()
      Await.result(Future(inputStream.read(arr)), timeout) should ===(-1)

      inputStream.close()
    }

    "return IOException when stream is failed" in assertAllStagesStopped {
      val sinkProbe = TestProbe()
      val (probe, (inputStream, _)) = TestSource.probe[ByteString].toMat(testSink(sinkProbe))(Keep.both).run()
      val ex = new RuntimeException("Stream failed.") with NoStackTrace
      sinkProbe.expectMsgClass(classOf[OnSubscribe])

      probe.sendNext(byteString)
      sinkProbe.expectMsgClass(classOf[OnNext])

      val arr = newArray()
      inputStream.read(arr)
      sinkProbe.expectMsgClass(classOf[InputStreamSubscriber.Read])

      probe.sendError(ex)
      sinkProbe.expectMsgClass(classOf[OnError])
      val p = Future(inputStream.read(arr))
      p.onFailure { case e ⇒ assert(e.isInstanceOf[IOException] && e.getCause.equals(ex)); Unit }
      p.onSuccess { case _ ⇒ fail() }
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val mat = ActorMaterializer()(sys)

      try {
        TestSource.probe[ByteString].runWith(InputStreamSink())(mat)
        mat.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "inputStreamSink").get
        assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
      } finally shutdown(sys)
    }
  }

}
