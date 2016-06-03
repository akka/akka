/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.io._

import akka.Done
import akka.actor.{ NoSerializationVerificationNeeded, ActorSystem }
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.{ ActorMaterializer, _ }
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.{ ActorMaterializerImpl, StreamSupervisor }
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink
import akka.util.{ ByteString, Timeout }
import akka.testkit.AkkaSpec

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class UnfoldResourceAsyncSourceSpec extends AkkaSpec(UnboundedMailboxConfig) {

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val manyLines = {
    ("a" * 100 + "\n") * 10 +
      ("b" * 100 + "\n") * 10 +
      ("c" * 100 + "\n") * 10 +
      ("d" * 100 + "\n") * 10 +
      ("e" * 100 + "\n") * 10 +
      ("f" * 100 + "\n") * 10
  }
  val manyLinesArray = manyLines.split("\n")

  val manyLinesFile = {
    val f = File.createTempFile("blocking-source-async-spec", ".tmp")
    new FileWriter(f).append(manyLines).close()
    f
  }

  val open: () ⇒ Future[BufferedReader] = () ⇒ Promise.successful(new BufferedReader(new FileReader(manyLinesFile))).future
  val read: (BufferedReader) ⇒ Future[Option[String]] = reader ⇒ Promise.successful(Option(reader.readLine())).future
  val close: (BufferedReader) ⇒ Future[Done] =
    reader ⇒ {
      reader.close()
      Promise.successful(Done).future
    }

  "Unfold Resource Async Source" must {
    "read contents from a file" in assertAllStagesStopped {
      val createPromise = Promise[BufferedReader]()
      val readPromise = Promise[Option[String]]()
      val closePromise = Promise[Done]()

      val createPromiseCalled = Promise[Done]()
      val readPromiseCalled = Promise[Done]()
      val closePromiseCalled = Promise[Done]()

      val resource = new BufferedReader(new FileReader(manyLinesFile))
      val p = Source.unfoldResourceAsync[String, BufferedReader](
        () ⇒ {
          createPromiseCalled.success(Done)
          createPromise.future
        },
        reader ⇒ {
          readPromiseCalled.success(Done)
          readPromise.future
        },
        reader ⇒ {
          closePromiseCalled.success(Done)
          closePromise.future
        })
        .runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[String]()
      p.subscribe(c)
      val sub = c.expectSubscription()

      sub.request(1)
      Await.ready(createPromiseCalled.future, 3.seconds)
      c.expectNoMsg(200.millis)
      createPromise.success(resource)

      val chunks = manyLinesArray.toList.iterator

      Await.ready(readPromiseCalled.future, 3.seconds)
      c.expectNoMsg(200.millis)
      readPromise.success(Option(resource.readLine()))
      c.expectNext() should ===(chunks.next())

      sub.cancel()
      Await.ready(closePromiseCalled.future, 3.seconds)
      resource.close()
      closePromise.success(Done)
    }

    "close resource successfully right after open" in assertAllStagesStopped {
      val createPromise = Promise[BufferedReader]()
      val readPromise = Promise[Option[String]]()
      val closePromise = Promise[Done]()

      val createPromiseCalled = Promise[Done]()
      val readPromiseCalled = Promise[Done]()
      val closePromiseCalled = Promise[Done]()

      val resource = new BufferedReader(new FileReader(manyLinesFile))
      val p = Source.unfoldResourceAsync[String, BufferedReader](
        () ⇒ {
          createPromiseCalled.success(Done)
          createPromise.future
        },
        reader ⇒ {
          readPromiseCalled.success(Done)
          readPromise.future
        },
        reader ⇒ {
          closePromiseCalled.success(Done)
          closePromise.future
        })
        .runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[String]()
      p.subscribe(c)
      val sub = c.expectSubscription()

      Await.ready(createPromiseCalled.future, 3.seconds)
      createPromise.success(resource)

      sub.cancel()
      Await.ready(closePromiseCalled.future, 3.seconds)
      resource.close()
      closePromise.success(Done)
    }

    "continue when Strategy is Resume and exception happened" in assertAllStagesStopped {
      val p = Source.unfoldResourceAsync[String, BufferedReader](
        open,
        reader ⇒ {
          val s = reader.readLine()
          if (s != null && s.contains("b")) throw TE("") else Promise.successful(Option(s)).future
        }, close).withAttributes(supervisionStrategy(resumingDecider))
        .runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[String]()

      p.subscribe(c)
      val sub = c.expectSubscription()

      (0 to 49).foreach(i ⇒ {
        sub.request(1)
        c.expectNext() should ===(if (i < 10) manyLinesArray(i) else manyLinesArray(i + 10))
      })
      sub.request(1)
      c.expectComplete()
    }

    "close and open stream again when Strategy is Restart" in assertAllStagesStopped {
      val p = Source.unfoldResourceAsync[String, BufferedReader](
        open,
        reader ⇒ {
          val s = reader.readLine()
          if (s != null && s.contains("b")) throw TE("") else Promise.successful(Option(s)).future
        }, close).withAttributes(supervisionStrategy(restartingDecider))
        .runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[String]()

      p.subscribe(c)
      val sub = c.expectSubscription()

      (0 to 19).foreach(i ⇒ {
        sub.request(1)
        c.expectNext() should ===(manyLinesArray(0))
      })
      sub.cancel()
    }

    "work with ByteString as well" in assertAllStagesStopped {
      val chunkSize = 50
      val buffer = Array.ofDim[Char](chunkSize)
      val p = Source.unfoldResourceAsync[ByteString, Reader](
        open,
        reader ⇒ {
          val p = Promise[Option[ByteString]]
          val s = reader.read(buffer)
          if (s > 0) p.success(Some(ByteString(buffer.mkString("")).take(s))) else p.success(None)
          p.future
        },
        reader ⇒ {
          reader.close()
          Promise.successful(Done).future
        }).runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[ByteString]()

      var remaining = manyLines
      def nextChunk() = {
        val (chunk, rest) = remaining.splitAt(chunkSize)
        remaining = rest
        chunk
      }

      p.subscribe(c)
      val sub = c.expectSubscription()

      (0 to 121).foreach(i ⇒ {
        sub.request(1)
        c.expectNext().utf8String should ===(nextChunk().toString)
      })
      sub.request(1)
      c.expectComplete()
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      try {
        val p = Source.unfoldResourceAsync[String, BufferedReader](
          open,
          read, close).runWith(TestSink.probe)(materializer)

        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "unfoldResourceSourceAsync").get
        try assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher") finally p.cancel()
      } finally shutdown(sys)
    }

    "fail when create throws exception" in assertAllStagesStopped {
      val p = Source.unfoldResourceAsync[String, BufferedReader](
        () ⇒ throw TE(""),
        read, close).runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[String]()
      p.subscribe(c)

      c.expectSubscription()
      c.expectError(TE(""))
    }

    "fail when close throws exception" in assertAllStagesStopped {
      val p = Source.unfoldResourceAsync[String, BufferedReader](
        open,
        read, reader ⇒ throw TE(""))
        .runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[String]()
      p.subscribe(c)

      val sub = c.expectSubscription()
      sub.request(61)
      c.expectNextN(60)
      c.expectError()
    }
  }
  override def afterTermination(): Unit = {
    manyLinesFile.delete()
  }
}
