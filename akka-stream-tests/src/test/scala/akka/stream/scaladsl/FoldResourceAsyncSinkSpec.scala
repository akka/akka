/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.io.{ BufferedWriter, File, FileWriter, Writer }
import java.util.concurrent.Executors

import akka.Done
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSource
import org.scalatest.BeforeAndAfter

import scala.concurrent.{ Await, ExecutionContext, Future, Promise }

class FoldResourceAsyncSinkSpec extends StreamSpec(UnboundedMailboxConfig) with BeforeAndAfter {
  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val ioExecutor = Executors.newFixedThreadPool(4)
  val ioExecutionContext = ExecutionContext.fromExecutor(ioExecutor)

  override def afterTermination(): Unit = {
    ioExecutor.shutdown()
  }

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

  val open: () ⇒ Future[Writer] = () ⇒ Future { isOpened = true; new BufferedWriter(new FileWriter(sinkFile)) }(ioExecutionContext)
  val write: (Writer, String) ⇒ Future[Unit] = (w, s) ⇒ Future(w.write(s))(ioExecutionContext)
  val close: Writer ⇒ Future[Unit] = w ⇒ Future { isClosed = true; w.close() }(ioExecutionContext)

  val writeErrorSync: (Writer, String) ⇒ Future[Unit] = (w, s) ⇒ {
    if (s.contains("b")) throw TE("")
    else Future(w.write(s))(ioExecutionContext)
  }
  val writeErrorAsync: (Writer, String) ⇒ Future[Unit] = (w, s) ⇒ {
    Future(if (s.contains("b")) throw TE("") else w.write(s))(ioExecutionContext)
  }

  "FoldResourceSinkAsync" must {
    "call open, write, close in order" in assertAllStagesStopped {
      case class DummyWriter()

      val openPromise = Promise[DummyWriter]
      val writePromise = Promise[Unit]
      val closePromise = Promise[Unit]

      val openCalled = Promise[Unit]
      val writeCalled = Promise[Unit]
      val closeCalled = Promise[Unit]

      val sink = Sink.foldResourceAsync[String, DummyWriter](
        () ⇒ {
          openCalled.success(())
          openPromise.future
        }, (writer, s) ⇒ {
          writeCalled.success(())
          writePromise.future
        }, writer ⇒ {
          closeCalled.success(())
          closePromise.future
        }
      )

      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()

      Await.result(openCalled.future, remainingOrDefault)
      openPromise.success(DummyWriter())

      writeCalled.isCompleted should be(false)
      probe.sendNext("A")
      Await.result(writeCalled.future, remainingOrDefault)
      writePromise.success(())

      closeCalled.isCompleted should be(false)
      fut.isCompleted should be(false)
      probe.sendComplete()
      Await.result(closeCalled.future, remainingOrDefault)
      closePromise.success(())

      Await.result(fut, remainingOrDefault)
    }

    "fail a materialized value when write throws an exception" in assertAllStagesStopped {
      val sink = Sink.foldResourceAsync[String, Writer](
        open,
        (w, s) ⇒ Future {
          if (s.contains("b")) throw TE("")
          else w.write(s)
        }(ioExecutionContext),
        close
      )

      val fut = Source.fromIterator(() ⇒ manyLines.grouped(64)).runWith(sink)
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
      (isOpened, isClosed) should be((true, true))
    }

    "write contents to a file" in assertAllStagesStopped {
      val sink = Sink.foldResourceAsync(open, write, close)
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

    "continue when Strategy is Resume and exception happened synchronously" in assertAllStagesStopped {
      val sink = Sink.foldResourceAsync[String, Writer](open, writeErrorSync, close)
        .withAttributes(supervisionStrategy(resumingDecider))
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

    "continue when Strategy is Resume and exception happened asynchronously" in assertAllStagesStopped {
      val sink = Sink.foldResourceAsync[String, Writer](open, writeErrorAsync, close)
        .withAttributes(supervisionStrategy(resumingDecider))
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

    "continue when Strategy is Restart and exception happened synchronously" in assertAllStagesStopped {
      val sink = Sink.foldResourceAsync[String, Writer](open, writeErrorSync, close)
        .withAttributes(supervisionStrategy(restartingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (isOpened, isClosed) should be((true, true))

      val src = scala.io.Source.fromFile(sinkFile)
      try {
        src.mkString should be(manyLinesArray.filterNot(line ⇒ line.contains("a") || line.contains("b")).mkString)
      } finally {
        src.close()
      }
    }

    "continue when Strategy is Restart and exception happened asynchronously" in assertAllStagesStopped {
      val sink = Sink.foldResourceAsync[String, Writer](open, writeErrorAsync, close)
        .withAttributes(supervisionStrategy(restartingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (isOpened, isClosed) should be((true, true))

      val src = scala.io.Source.fromFile(sinkFile)
      try {
        src.mkString should be(manyLinesArray.filterNot(line ⇒ line.contains("a") || line.contains("b")).mkString)
      } finally {
        src.close()
      }
    }

    "fail when upstream fails" in assertAllStagesStopped {
      val sink = Sink.foldResourceAsync(open, write, close)
      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()
      probe.sendError(TE(""))
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
      (isOpened, isClosed) should be((true, true))
    }
  }
}
