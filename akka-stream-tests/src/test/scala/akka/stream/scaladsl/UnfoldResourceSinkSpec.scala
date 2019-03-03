/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.LongAdder

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils._
import akka.stream.testkit.javadsl.TestSource
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.{ ActorMaterializer, _ }
import com.google.common.jimfs.{ Configuration, Jimfs }

import scala.collection.JavaConverters._
import scala.concurrent.{ Await, Future }

class UnfoldResourceSinkSpec extends StreamSpec(UnboundedMailboxConfig) {
  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  private val fs = Jimfs.newFileSystem("UnfoldResourceSinkSpec", Configuration.unix())

  private val r = Range(1, 100)
  private val manyLines = r.map(i ⇒ s"Link $i").toVector

  private def tmpPath = Files.createTempFile(fs.getPath("/"), "tmp", ".txt")
  private def newBufferedWriter(path: Path) = Files.newBufferedWriter(path, StandardCharsets.UTF_8)

  "Unfold Resource Sink" must {
    "write contents to a file" in assertAllStagesStopped {
      val path = tmpPath
      val closedCount = new LongAdder

      val sink: Sink[String, Future[Done]] = Sink.unfoldResource[String, BufferedWriter](
        () ⇒ newBufferedWriter(path),
        (writer, line) ⇒ writer.write(s"$line\n"),
        writer ⇒ {
          closedCount.increment()
          writer.close()
        })

      val future = Source(manyLines).runWith(sink)
      Await.result(future, remainingOrDefault) shouldEqual Done

      closedCount.sum() shouldBe 1
      Files.newBufferedReader(path).lines().iterator().asScala.zip(r.iterator).foreach {
        case (line, want) ⇒ line shouldEqual s"Link $want"
      }
    }

    "work from an empty source" in assertAllStagesStopped {
      val path = tmpPath
      val closedCount = new LongAdder

      val sink: Sink[String, Future[Done]] = Sink.unfoldResource[String, BufferedWriter](
        () ⇒ newBufferedWriter(path),
        (writer, line) ⇒ writer.write(s"$line\n"),
        writer ⇒ {
          closedCount.increment()
          writer.close()
        })

      val future = Source.empty.runWith(sink)
      Await.result(future, remainingOrDefault) shouldEqual Done

      closedCount.sum() shouldBe 1
      Files.readAllBytes(path).length shouldBe 0
    }

    "stop when Strategy is Stop and exception happened" in assertAllStagesStopped {
      val path = tmpPath
      val closedCount = new LongAdder

      val sink = Sink.unfoldResource[String, BufferedWriter](
        () ⇒ newBufferedWriter(path),
        (writer, line) ⇒ {
          if (line.endsWith("89")) {
            throw TE("Skip Line")
          }
          writer.write(s"$line\n")
        },
        writer ⇒ {
          closedCount.increment()
          writer.close()
        }).withAttributes(supervisionStrategy(stoppingDecider))

      val future = Source(manyLines).runWith(sink)
      Await.result(future.failed, remainingOrDefault).getMessage shouldEqual "Skip Line"

      closedCount.sum() shouldBe 1
      Files.newBufferedReader(path).lines().iterator().asScala.zip(r.iterator.take(88)).foreach {
        case (line, want) ⇒
          line shouldEqual s"Link $want"
      }
    }

    "continue when Strategy is Resume and exception happened" in assertAllStagesStopped {
      val path = tmpPath
      val closedCount = new LongAdder

      val sink = Sink.unfoldResource[String, BufferedWriter](
        () ⇒ newBufferedWriter(path),
        (writer, line) ⇒ {
          if (line.endsWith("89")) {
            throw TE("Skip Line")
          }
          writer.write(s"$line\n")
        },
        writer ⇒ {
          closedCount.increment()
          writer.close()
        }).withAttributes(supervisionStrategy(resumingDecider))

      val future = Source(manyLines).runWith(sink)
      Await.result(future, remainingOrDefault) shouldEqual Done

      closedCount.sum() shouldBe 1
      Files.newBufferedReader(path).lines().iterator().asScala.zip(r.iterator).foreach {
        case (line, n) ⇒
          val want = if (n >= 89) { n + 1 } else n
          line shouldEqual s"Link $want"
      }
    }

    "close and open stream again when Strategy is Restart" in assertAllStagesStopped {
      val path = tmpPath
      val closedCount = new LongAdder

      val sink = Sink.unfoldResource[String, BufferedWriter](
        () ⇒ newBufferedWriter(path),
        (writer, line) ⇒ {
          if (line.endsWith("89")) {
            throw TE("Skip Line")
          }
          writer.write(s"$line\n")
        },
        writer ⇒ {
          closedCount.increment()
          writer.close()
        }).withAttributes(supervisionStrategy(restartingDecider))

      val future = Source(manyLines).runWith(sink)
      Await.result(future, remainingOrDefault) shouldEqual Done

      closedCount.sum() shouldBe 2
      Files.newBufferedReader(path).lines().iterator().asScala.zip(r.iterator.drop(89)).foreach {
        case (line, want) ⇒
          line shouldEqual s"Link $want"
      }
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      try {
        val p = Sink.unfoldResource[String, Integer](
          () ⇒ 1,
          (_, _) ⇒ (),
          _ ⇒ ()).runWith(TestSource.probe(sys))(materializer)

        materializer.asInstanceOf[PhasedFusingActorMaterializer].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString.contains("unfoldResourceSink")).get
        try assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher") finally p.sendComplete()
      } finally shutdown(sys)
    }

    "fail when create throws exception" in assertAllStagesStopped {
      val closedCount = new LongAdder

      val sink: Sink[String, Future[Done]] = Sink.unfoldResource[String, Int](
        () ⇒ throw TE("Test"),
        (_, _) ⇒ (),
        _ ⇒ {
          closedCount.increment()
        })

      val future = Source(manyLines).runWith(sink)
      Await.result(future.failed, remainingOrDefault).getMessage shouldEqual "Test"

      closedCount.sum() shouldBe 0
    }

    "fail when close throws exception" in assertAllStagesStopped {
      val closedCount = new LongAdder

      val sink: Sink[String, Future[Done]] = Sink.unfoldResource[String, Int](
        () ⇒ 1,
        (_, _) ⇒ (),
        _ ⇒ {
          closedCount.increment()
          throw TE("Test")
        })

      val future = Source(manyLines.take(3)).runWith(sink)
      Await.result(future.failed, remainingOrDefault).getMessage shouldEqual "Test"

      closedCount.sum() shouldBe 1
    }

    "not close the resource twice when read fails and then close fails" in {
      val closedCount = new LongAdder

      val sink: Sink[String, Future[Done]] = Sink.unfoldResource[String, Int](
        () ⇒ 1,
        (_, _) ⇒ throw TE("Test Write"),
        _ ⇒ {
          closedCount.increment()
          throw TE("Test Closed")
        })

      val future = Source(manyLines.take(3)).runWith(sink)
      Await.result(future.failed, remainingOrDefault).getMessage shouldEqual "Test Write"

      closedCount.sum() shouldBe 1
    }
  }

  override def afterTermination(): Unit = {
    fs.close()
  }
}
