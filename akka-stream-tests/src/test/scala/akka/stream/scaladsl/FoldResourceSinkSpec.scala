/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.{ ActorMaterializerImpl, StreamSupervisor }
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSource
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import org.scalatest.BeforeAndAfter

import scala.concurrent.Await

class FoldResourceSinkSpec extends StreamSpec(UnboundedMailboxConfig) with BeforeAndAfter {

  class TestResource[T] {
    var isOpened = false
    var isClosed = false
    var written: Vector[T] = _

    def open(): TestResource[T] = {
      isOpened = true
      isClosed = false
      // written is cleared on open
      written = Vector.empty[T]
      this
    }

    def write(t: T): Unit = {
      if (!isOpened) {
        sys.error("Writing to a not opened resource")
      }
      if (isClosed) {
        sys.error("Writing to a closed resource")
      }
      written = written :+ t
    }

    def close(): Unit = {
      if (!isOpened) {
        sys.error("Closing a not opened resource")
      }
      isClosed = true
    }
  }

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

  "Fold Resource Sink" must {
    "write contents to a file" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResource[String, TestResource[String]](() ⇒ r.open(), _ write _, _.close())
      val fut = Source.fromIterator(() ⇒ manyLines.grouped(64)).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))
      r.written.mkString should be(manyLines)
    }

    "continue when Strategy is Resume and exception happened" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResource[String, TestResource[String]](
        () ⇒ r.open(),
        (writer, s) ⇒ if (s.contains("b")) throw TE("") else writer.write(s),
        _.close()
      ).withAttributes(supervisionStrategy(resumingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))

      r.written.mkString should be(manyLinesArray.filterNot(_.contains("b")).mkString)
    }

    "close and open stream again when Strategy is Restart" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResource[String, TestResource[String]](
        () ⇒ r.open(),
        (writer, s) ⇒ if (s.contains("b")) throw TE("") else writer.write(s),
        _.close()
      ).withAttributes(supervisionStrategy(restartingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))

      r.written.mkString should be(
        manyLinesArray.filterNot(line ⇒ line.contains("a") || line.contains("b")).mkString)
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      try {
        val sink = Sink.foldResource[String, TestResource[String]](() ⇒ r.open(), _ write _, _.close())
        val probe = TestSource.probe[String].to(sink).run()(materializer)

        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "foldResourceSink").get
        try assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher") finally probe.sendComplete()
      } finally shutdown(sys)
    }

    "fail when open throws exception" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResource[String, TestResource[String]](
        () ⇒ throw TE(""),
        _ write _,
        _.close()
      )

      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()

      Await.result(fut.failed, remainingOrDefault) should be(TE(""))

      // Open is failed. It should not be closed
      r.isClosed should be(false)
      probe.expectCancellation()
    }

    "fail when close throws exception" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResource[String, TestResource[String]](
        () ⇒ r.open(),
        _ write _,
        r ⇒ throw TE(""))
      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()

      probe.sendComplete()
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
    }

    "fail when upstream fails" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResource[String, TestResource[String]](() ⇒ r.open(), _ write _, _.close())
      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()
      probe.sendError(TE(""))
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
      (r.isOpened, r.isClosed) should be((true, true))
    }
  }

}
