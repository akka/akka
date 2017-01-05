/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.io

import java.nio.file.{ FileSystems, Files }
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Random

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorAttributes
import akka.stream.Attributes
import akka.stream.impl.ActorMaterializerImpl
import akka.stream.impl.StreamSupervisor
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.io.FileSourceSpec.Settings
import akka.stream.scaladsl.{ FileIO, Keep, Sink }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestDuration
import akka.util.ByteString
import akka.util.Timeout
import com.google.common.jimfs.{ Configuration, Jimfs }

import scala.concurrent.Await
import scala.concurrent.duration._

object FileSourceSpec {
  final case class Settings(chunkSize: Int, readAhead: Int)
}

class FileSourceSpec extends StreamSpec(UnboundedMailboxConfig) {

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val fs = Jimfs.newFileSystem("FileSourceSpec", Configuration.unix())

  val TestText = {
    ("a" * 1000) +
      ("b" * 1000) +
      ("c" * 1000) +
      ("d" * 1000) +
      ("e" * 1000) +
      ("f" * 1000)
  }

  val testFile = {
    val f = Files.createTempFile(fs.getPath("/"), "file-source-spec", ".tmp")
    Files.newBufferedWriter(f, UTF_8).append(TestText).close()
    f
  }

  val notExistingFile = {
    // this way we make sure it doesn't accidentally exist
    val f = Files.createTempFile(fs.getPath("/"), "not-existing-file", ".tmp")
    Files.delete(f)
    f
  }

  val LinesCount = 2000 + new Random().nextInt(300)

  val manyLines = {
    val f = Files.createTempFile(fs.getPath("/"), s"file-source-spec-lines_$LinesCount", "tmp")
    val w = Files.newBufferedWriter(f, UTF_8)
    (1 to LinesCount).foreach { l ⇒
      w.append("a" * l).append("\n")
    }
    w.close()
    f
  }

  "FileSource" must {
    "read contents from a file" in assertAllStagesStopped {
      val chunkSize = 512
      val bufferAttributes = Attributes.inputBuffer(1, 2)

      val p = FileIO.fromPath(testFile, chunkSize)
        .withAttributes(bufferAttributes)
        .runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[ByteString]()
      p.subscribe(c)
      val sub = c.expectSubscription()

      var remaining = TestText
      def nextChunk() = {
        val (chunk, rest) = remaining.splitAt(chunkSize)
        remaining = rest
        chunk
      }

      sub.request(1)
      c.expectNext().utf8String should ===(nextChunk().toString)
      sub.request(1)
      c.expectNext().utf8String should ===(nextChunk().toString)
      c.expectNoMsg(300.millis)

      sub.request(200)
      var expectedChunk = nextChunk().toString
      while (expectedChunk != "") {
        c.expectNext().utf8String should ===(expectedChunk)
        expectedChunk = nextChunk().toString
      }
      sub.request(1)

      c.expectComplete()
    }

    "complete only when all contents of a file have been signalled" in assertAllStagesStopped {
      val chunkSize = 256
      val bufferAttributes = Attributes.inputBuffer(4, 8)

      val demandAllButOneChunks = TestText.length / chunkSize - 1

      val p = FileIO.fromPath(testFile, chunkSize)
        .withAttributes(bufferAttributes)
        .runWith(Sink.asPublisher(false))

      val c = TestSubscriber.manualProbe[ByteString]()
      p.subscribe(c)
      val sub = c.expectSubscription()

      var remaining = TestText
      def nextChunk() = {
        val (chunk, rest) = remaining.splitAt(chunkSize)
        remaining = rest
        chunk
      }

      sub.request(demandAllButOneChunks)
      for (i ← 1 to demandAllButOneChunks) c.expectNext().utf8String should ===(nextChunk())
      c.expectNoMsg(300.millis)

      sub.request(1)
      c.expectNext().utf8String should ===(nextChunk())
      c.expectNoMsg(200.millis)

      sub.request(1)
      c.expectNext().utf8String should ===(nextChunk())
      c.expectComplete()
    }

    "onError with failure and return a failed IOResult when trying to read from file which does not exist" in assertAllStagesStopped {
      val (r, p) = FileIO.fromPath(notExistingFile).toMat(Sink.asPublisher(false))(Keep.both).run()
      val c = TestSubscriber.manualProbe[ByteString]()
      p.subscribe(c)

      c.expectSubscription()
      c.expectError()
      val ioResult = Await.result(r, 3.seconds.dilated).status.isFailure shouldBe true
    }

    List(
      Settings(chunkSize = 512, readAhead = 2),
      Settings(chunkSize = 512, readAhead = 4),
      Settings(chunkSize = 2048, readAhead = 2),
      Settings(chunkSize = 2048, readAhead = 4)) foreach { settings ⇒
        import settings._

        s"count lines in real file (chunkSize = $chunkSize, readAhead = $readAhead)" in {
          val s = FileIO.fromPath(manyLines, chunkSize = chunkSize)
            .withAttributes(Attributes.inputBuffer(readAhead, readAhead))

          val f = s.runWith(Sink.fold(0) { case (acc, l) ⇒ acc + l.utf8String.count(_ == '\n') })

          val lineCount = Await.result(f, 3.seconds)
          lineCount should ===(LinesCount)
        }
      }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      try {
        val p = FileIO.fromPath(manyLines).runWith(TestSink.probe)(materializer)

        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "fileSource").get
        try assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher") finally p.cancel()
      } finally shutdown(sys)
    }

    //FIXME: overriding dispatcher should be made available with dispatcher alias support in materializer (#17929)
    "allow overriding the dispatcher using Attributes" in {
      pending
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)
      implicit val timeout = Timeout(500.millis)

      try {
        val p = FileIO.fromPath(manyLines)
          .withAttributes(ActorAttributes.dispatcher("akka.actor.default-dispatcher"))
          .runWith(TestSink.probe)(materializer)

        materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "File").get
        try assertDispatcher(ref, "akka.actor.default-dispatcher") finally p.cancel()
      } finally shutdown(sys)
    }

    "not signal onComplete more than once" in {
      FileIO.fromPath(testFile, 2 * TestText.length)
        .runWith(TestSink.probe)
        .requestNext(ByteString(TestText, UTF_8.name))
        .expectComplete()
        .expectNoMsg(1.second)
    }
  }

  override def afterTermination(): Unit = {
    fs.close()
  }

}
