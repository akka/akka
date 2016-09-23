/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.io.{ BufferedWriter, File, FileWriter, Writer }

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

  var sinkFile: File = _
  var isOpened: Boolean = _
  var isClosed: Boolean = _
  before {
    sinkFile = File.createTempFile("blocking-sink-spec", ".tmp")
    isOpened = false
    isClosed = false
  }
  after {
    sinkFile.delete()
  }

  val open: () ⇒ Writer = () ⇒ {
    isOpened = true; new BufferedWriter(new FileWriter(sinkFile))
  }
  val write: (Writer, String) ⇒ Unit = _ write _
  val close: Writer ⇒ Unit = writer ⇒ {
    isClosed = true; writer.close()
  }

  "Fold Resource Sink" must {
    "write contents to a file" in assertAllStagesStopped {
      val sink = Sink.foldResource(open, write, close)
      val fut = Source.fromIterator(() ⇒ manyLines.grouped(64)).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (isOpened, isClosed) should be((true, true))

      val src = scala.io.Source.fromFile(sinkFile)
      try {
        src.mkString should be(manyLines)
      } finally {
        src.close()
      }
    }

    "continue when Strategy is Resume and exception happened" in assertAllStagesStopped {
      val sink = Sink.foldResource[String, Writer](
        open,
        (writer, s) ⇒ if (s.contains("b")) throw TE("") else writer.write(s),
        close
      ).withAttributes(supervisionStrategy(resumingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (isOpened, isClosed) should be((true, true))

      val src = scala.io.Source.fromFile(sinkFile)
      try {
        src.mkString should be(manyLinesArray.filterNot(_.contains("b")).mkString)
      } finally {
        src.close()
      }
    }

    "close and open stream again when Strategy is Restart" in assertAllStagesStopped {
      val sink = Sink.foldResource[String, Writer](
        open,
        (writer, s) ⇒ if (s.contains("b")) throw TE("") else writer.write(s),
        close
      ).withAttributes(supervisionStrategy(restartingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (isOpened, isClosed) should be((true, true))

      val src = scala.io.Source.fromFile(sinkFile)
      try {
        // "a" lines should be overwritten
        src.mkString should be(manyLinesArray.filterNot(line ⇒ line.contains("a") || line.contains("b")).mkString)
      } finally {
        src.close()
      }
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      try {
        val sink = Sink.foldResource(open, write, close)
        val probe = TestSource.probe[String].to(sink).run()(materializer)

        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "foldResourceSink").get
        try assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher") finally probe.sendComplete()
      } finally shutdown(sys)
    }

    "fail when open throws exception" in assertAllStagesStopped {
      val sink = Sink.foldResource[String, Writer](
        () ⇒ throw TE(""),
        write,
        close
      )

      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()

      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
      isClosed should be(false)
      probe.expectCancellation()
    }

    "fail when close throws exception" in assertAllStagesStopped {
      val sink = Sink.foldResource[String, Writer](
        open,
        write,
        writer ⇒ throw TE(""))
      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()

      probe.sendComplete()
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
    }

    "fail when upstream fails" in assertAllStagesStopped {
      val sink = Sink.foldResource(open, write, close)
      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()
      probe.sendError(TE(""))
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
      (isOpened, isClosed) should be((true, true))
    }
  }

}
