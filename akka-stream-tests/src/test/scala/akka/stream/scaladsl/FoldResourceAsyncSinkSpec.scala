/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.Done
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSource
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import org.scalatest.BeforeAndAfter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future, Promise }

class FoldResourceAsyncSinkSpec extends StreamSpec(UnboundedMailboxConfig) with BeforeAndAfter {

  class TestResource[T] {

    var autoCompleteOpen: Boolean = true
    var autoCompleteWrite: Boolean = true
    var autoCompleteClose: Boolean = true

    @volatile var isOpened = false
    @volatile var isClosed = false
    @volatile var written: Vector[T] = _
    @volatile var completeOpen: Promise[Unit] = _
    @volatile var completeWrite: Promise[Unit] = _
    @volatile var completeClose: Promise[Unit] = _

    def open(): Future[TestResource[T]] = {
      def syncOpen(): TestResource[T] = {
        isOpened = true
        isClosed = false
        // written is cleared on open
        written = Vector.empty[T]
        this
      }

      if (autoCompleteOpen) {
        Future.successful(syncOpen())
      } else {
        completeOpen = Promise[Unit]
        completeOpen.future.map { _ ⇒
          syncOpen()
        }
      }
    }

    def write(t: T): Future[Unit] = {
      def syncWrite(): Unit = {
        if (!isOpened) {
          sys.error("Writing to a not opened resource")
        }
        if (isClosed) {
          sys.error("Writing to a closed resource")
        }
        written = written :+ t
      }

      if (autoCompleteWrite) {
        Future.successful(syncWrite())
      } else {
        completeWrite = Promise[Unit]
        completeWrite.future.map { _ ⇒
          syncWrite()
        }
      }
    }

    def close(): Future[Unit] = {
      def syncClose(): Unit = {
        if (!isOpened) {
          sys.error("Closing a not opened resource")
        }
        isClosed = true
      }
      if (autoCompleteClose) {
        Future.successful(syncClose())
      } else {
        completeClose = Promise[Unit]
        completeClose.future.map { _ ⇒
          syncClose()
        }
      }
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
      val r = new TestResource[String]()
      val sink = Sink.foldResourceAsync[String, TestResource[String]](
        () ⇒ r.open(),
        (w, s) ⇒ Future {
          if (s.contains("b")) throw TE("")
          else w.write(s)
        },
        _.close()
      )

      val fut = Source.fromIterator(() ⇒ manyLines.grouped(64)).runWith(sink)
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
      (r.isOpened, r.isClosed) should be((true, true))
    }

    "write contents to a resource" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResourceAsync[String, TestResource[String]](() ⇒ r.open(), _ write _, _.close())
      val fut = Source.fromIterator(() ⇒ manyLines.grouped(64)).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))
      r.written.mkString should be(manyLines)
    }

    "continue when Strategy is Resume and exception happened synchronously" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResourceAsync[String, TestResource[String]](
        () ⇒ r.open(),
        (r, s) ⇒ if (s.contains("b")) throw TE("") else r.write(s),
        _.close()
      )
        .withAttributes(supervisionStrategy(resumingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))
      r.written.mkString should be(manyLinesArray.filterNot(_.contains("b")).mkString)
    }

    "continue when Strategy is Resume and exception happened asynchronously" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResourceAsync[String, TestResource[String]](
        () ⇒ r.open(),
        (r, s) ⇒ Future(if (s.contains("b")) throw TE("")).flatMap(_ ⇒ r.write(s)),
        _.close()
      )
        .withAttributes(supervisionStrategy(resumingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))
      r.written.mkString should be(manyLinesArray.filterNot(_.contains("b")).mkString)
    }

    "continue when Strategy is Restart and exception happened synchronously" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResourceAsync[String, TestResource[String]](
        () ⇒ r.open(),
        (r, s) ⇒ if (s.contains("b")) throw TE("") else r.write(s),
        _.close()
      )
        .withAttributes(supervisionStrategy(restartingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))
      r.written.mkString should be(manyLinesArray.filterNot(line ⇒ line.contains("a") || line.contains("b")).mkString)

    }

    "continue when Strategy is Restart and exception happened asynchronously" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResourceAsync[String, TestResource[String]](
        () ⇒ r.open(),
        (r, s) ⇒ Future(if (s.contains("b")) throw TE("")).flatMap(_ ⇒ r.write(s)),
        _.close()
      )
        .withAttributes(supervisionStrategy(restartingDecider))
      val fut = Source.fromIterator(() ⇒ manyLinesArray.iterator).runWith(sink)

      Await.result(fut, remainingOrDefault) should be(Done)
      (r.isOpened, r.isClosed) should be((true, true))
      r.written.mkString should be(manyLinesArray.filterNot(line ⇒ line.contains("a") || line.contains("b")).mkString)
    }

    "fail when upstream fails" in assertAllStagesStopped {
      val r = new TestResource[String]()
      val sink = Sink.foldResourceAsync[String, TestResource[String]](() ⇒ r.open(), _ write _, _.close)
      val (probe, fut) = TestSource.probe[String].toMat(sink)(Keep.both).run()
      probe.sendError(TE(""))
      Await.result(fut.failed, remainingOrDefault) should be(TE(""))
      (r.isOpened, r.isClosed) should be((true, true))
    }

  }
}
