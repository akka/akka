/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.nowarn
import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise
import scala.util.Success
import scala.util.control.NoStackTrace

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs

import akka.Done
import akka.stream.AbruptTerminationException
import akka.stream.ActorAttributes
import akka.stream.ActorAttributes._
import akka.stream.ActorMaterializer
import akka.stream.Supervision
import akka.stream.Supervision._
import akka.stream.SystemMaterializer
import akka.stream.impl.PhasedFusingActorMaterializer
import akka.stream.impl.StreamSupervisor
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.EventFilter
import akka.util.ByteString

class FoldResourceSpec extends StreamSpec(UnboundedMailboxConfig) {

  private val fs = Jimfs.newFileSystem("FoldResourceSpec", Configuration.unix())
  private val ex = new Exception("TEST") with NoStackTrace

  private val manyLines = {
    val b = ListBuffer[String]()
    b.append("a" * 1000 + "\n")
    b.append("b" * 1000 + "\n")
    b.append("c" * 1000 + "\n")
    b.append("d" * 1000 + "\n")
    b.append("e" * 1000 + "\n")
    b.append("f" * 1000 + "\n")
    b.toList
  }

  private def newBufferWriter(path: Path) =
    Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.APPEND)

  "FoldResource" must {
    "can write contents to a file" in {
      targetFile { path =>
        val completion = Source(manyLines).runWith(
          Sink
            .foldResource(() => newBufferWriter(path))((writer, line) => writer.write(line), writer => writer.close()))
        completion.futureValue shouldBe Done
        checkFileContents(path, manyLines.mkString(""))
      }
    }

    "continue when Strategy is Resume and exception happened" in {
      targetFile { path =>
        val completion = Source(manyLines).runWith(
          Sink
            .foldResource[BufferedWriter, String](() => newBufferWriter(path))((writer, line) => {
              if (line.contains("b")) throw TE("")
              writer.write(line)
            }, writer => writer.close())
            .withAttributes(supervisionStrategy(resumingDecider)))
        completion.futureValue shouldBe Done
        checkFileContents(path, (manyLines.take(1) ++ manyLines.drop(2)).mkString(""))
      }
    }

    "close and open stream again when Strategy is Restart" in {
      targetFile { path =>
        val completion = Source(manyLines).runWith(
          Sink
            .foldResource[BufferedWriter, String](() => newBufferWriter(path))((writer, line) => {
              if (line.contains("b")) throw TE("")
              writer.write(line)
            }, writer => writer.close())
            .withAttributes(supervisionStrategy(stoppingDecider)))
        completion.failed.futureValue shouldBe a[TE]
        checkFileContents(path, manyLines.take(1).mkString(""))
      }
    }

    "work with ByteString as well" in {
      targetFile { path =>
        val completion = Source(manyLines)
          .map(ByteString(_))
          .runWith(
            Sink.foldResource(() => newBufferWriter(path))(
              (writer, bs) => writer.write(bs.utf8String),
              writer => writer.close()))
        completion.futureValue shouldBe Done
        checkFileContents(path, manyLines.mkString(""))
      }
    }

    "use dedicated blocking-io-dispatcher by default" in {
      targetFile { path =>
        val (pub, completion) = TestSource[String]()
          .toMat(Sink
            .foldResource(() => newBufferWriter(path))((writer, line) => writer.write(line), writer => writer.close()))(
            Keep.both)
          .run()

        SystemMaterializer(system).materializer
          .asInstanceOf[PhasedFusingActorMaterializer]
          .supervisor
          .tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "foldResourceSink").get
        val subscription = pub.expectSubscription()
        subscription.expectRequest()
        subscription.sendNext("hello")
        try assertDispatcher(ref, ActorAttributes.IODispatcher.dispatcher)
        finally {
          subscription.sendComplete()
        }
        completion.futureValue shouldBe Done
        checkFileContents(path, "hello")
      }

    }

    "fail when create throws exception" in {
      val (pub, completion) = TestSource[String]()
        .toMat(
          Sink.foldResource[BufferedWriter, String](() => throw ex)(
            (writer, line) => writer.write(line),
            writer => writer.close()))(Keep.both)
        .run()
      pub.expectCancellationWithCause(ex)
      completion.failed.futureValue shouldBe (ex)
    }

    "fail when close throws exception" in {
      val completion =
        Source.single(1).runWith(Sink.foldResource(() => Iterator("a"))((_, _) => (), _ => throw TE("")))
      completion.failed.futureValue shouldBe a[TE]
    }

    "not close the resource twice when read fails" in {
      val closedCounter = new AtomicInteger(0)
      val completion = Source
        .repeat(1)
        .runWith(
          Sink.foldResource(() => 23)( // the best resource there is
            (_, _) => throw TE("failing read"),
            _ => {
              closedCounter.incrementAndGet()
              None
            }))
      completion.failed.futureValue shouldBe a[TE]
      closedCounter.get() should ===(1)
    }

    "not close the resource twice when read fails and then close fails" in {
      val closedCounter = new AtomicInteger(0)
      val completion = Source
        .repeat(1)
        .runWith(Sink.foldResource(() => 23)((_, _) => throw TE("failing read"), _ => {
          closedCounter.incrementAndGet()
          if (closedCounter.get == 1) throw TE("boom")
          None
        }))

      completion.failed.futureValue shouldBe a[TE]
      closedCounter.get() should ===(1)
    }

    "will close the resource when upstream complete" in {
      val closedCounter = new AtomicInteger(0)
      val (pub, completion) = TestSource[Int]()
        .toMat(Sink.foldResource(() => 23)((_, _) => (), _ => {
          closedCounter.incrementAndGet()
        }))(Keep.both)
        .run()
      pub.sendNext(1)
      pub.sendComplete()
      completion.futureValue shouldBe Done
      closedCounter.get shouldBe (1)
    }

    "will close the resource when upstream fail" in {
      val closedCounter = new AtomicInteger(0)
      val (pub, completion) = TestSource[Int]()
        .toMat(Sink.foldResource(() => 23)((_, _) => (), _ => {
          closedCounter.incrementAndGet()
        }))(Keep.both)
        .run()
      pub.sendNext(1)
      pub.sendError(ex)
      completion.failed.futureValue shouldBe ex
      closedCounter.get shouldBe (1)
    }

    "will close the resource on abrupt materializer termination" in {
      @nowarn("msg=deprecated")
      val mat = ActorMaterializer()
      targetFile { path =>
        val promise = Promise[Done]()
        val matVal = TestSource[String]().runWith(Sink.foldResource(() => {
          newBufferWriter(path)
        })((writer, str) => writer.write(str), writer => {
          writer.close()
          promise.complete(Success(Done))
        }))(mat)
        mat.shutdown()
        matVal.failed.futureValue shouldBe an[AbruptTerminationException]
        promise.future.futureValue shouldBe Done
      }
    }

    "not allow null resource" in {
      EventFilter[NullPointerException](occurrences = 1).intercept {
        Source
          .single("one")
          .runWith(Sink.foldResource(() => null: String)((_, _) => (), _ => None))
          .failed
          .futureValue shouldBe a[NullPointerException]
      }
    }

    "not allow null resource on restart" in {
      val counter = new AtomicInteger(0)
      EventFilter[NullPointerException](occurrences = 1).intercept {
        Source
          .single("one")
          .runWith(
            Sink
              .foldResource[String, String](() => if (counter.incrementAndGet() == 1) "state" else null)(
                (_, _) => throw TE("boom"),
                _ => None)
              .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider)))
          .failed
          .futureValue shouldBe a[NullPointerException]
      }
    }

  }
  private def targetFile(block: Path => Unit, create: Boolean = true): Unit = {
    val targetFile = Files.createTempFile(fs.getPath("/"), "fold-resource", ".tmp")
    if (!create) Files.delete(targetFile)
    try block(targetFile)
    finally Files.delete(targetFile)
  }

  def checkFileContents(f: Path, contents: String): Unit = {
    val out = Files.readAllBytes(f)
    new String(out) should ===(contents)
  }

  override def afterTermination(): Unit = {
    fs.close()
  }
}
