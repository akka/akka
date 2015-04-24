/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.{ File, FileWriter }
import java.util.Random

import akka.actor.{ ActorCell, RepointableActorRef, ActorSystem }
import akka.stream.io.SynchronousFileSourceSpec.Settings
import akka.stream.scaladsl.Sink
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.{ ActorOperationAttributes, ActorFlowMaterializer, ActorFlowMaterializerSettings, OperationAttributes }
import akka.util.{ Timeout, ByteString }

import scala.concurrent.Await
import scala.concurrent.duration._

object SynchronousFileSourceSpec {
  final case class Settings(chunkSize: Int, readAhead: Int)
}

class SynchronousFileSourceSpec extends AkkaSpec(UnboundedMailboxConfig) {

  val settings = ActorFlowMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorFlowMaterializer(settings)

  val TestText = {
    ("a" * 1000) +
      ("b" * 1000) +
      ("c" * 1000) +
      ("d" * 1000) +
      ("e" * 1000) +
      ("f" * 1000)
  }

  val testFile = {
    val f = File.createTempFile("file-source-spec", ".tmp")
    new FileWriter(f).append(TestText).close()
    f
  }

  val notExistingFile = {
    // this way we make sure it doesn't accidentally exist
    val f = File.createTempFile("not-existing-file", ".tmp")
    f.delete()
    f
  }

  val LinesCount = 2000 + new Random().nextInt(300)

  val manyLines = {
    val f = File.createTempFile(s"file-source-spec-lines_$LinesCount", "tmp")
    val w = new FileWriter(f)
    (1 to LinesCount).foreach { l ⇒
      w.append("a" * l).append("\n")
    }
    w.close()
    f
  }

  "File Source" must {
    "read contents from a file" in assertAllStagesStopped {
      val chunkSize = 512
      val bufferAttributes = OperationAttributes.inputBuffer(1, 2)

      val p = SynchronousFileSource(testFile, chunkSize)
        .withAttributes(bufferAttributes)
        .runWith(Sink.publisher)
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
      val bufferAttributes = OperationAttributes.inputBuffer(4, 8)

      val demandAllButOneChunks = TestText.length / chunkSize - 1

      val p = SynchronousFileSource(testFile, chunkSize)
        .withAttributes(bufferAttributes)
        .runWith(Sink.publisher)

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

    "onError whent trying to read from file which does not exist" in assertAllStagesStopped {
      val p = SynchronousFileSource(notExistingFile).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[ByteString]()
      p.subscribe(c)

      c.expectSubscription()
      c.expectError()
    }

    List(
      Settings(chunkSize = 512, readAhead = 2),
      Settings(chunkSize = 512, readAhead = 4),
      Settings(chunkSize = 2048, readAhead = 2),
      Settings(chunkSize = 2048, readAhead = 4)) foreach { settings ⇒
        import settings._

        s"count lines in real file (chunkSize = $chunkSize, readAhead = $readAhead)" in {
          val s = SynchronousFileSource(manyLines, chunkSize = chunkSize)
            .withAttributes(OperationAttributes.inputBuffer(readAhead, readAhead))

          val f = s.runWith(Sink.fold(0) { case (acc, l) ⇒ acc + l.utf8String.count(_ == '\n') })

          val lineCount = Await.result(f, 3.seconds)
          lineCount should ===(LinesCount)
        }
      }

    "use dedicated file-io-dispatcher by default" in {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val mat = ActorFlowMaterializer()(sys)
      implicit val timeout = Timeout(500.millis)

      try {
        SynchronousFileSource(manyLines).runWith(Sink.ignore)(mat)

        val ref = Await.result(sys.actorSelection("/user/$a/flow-*").resolveOne(), timeout.duration)
        ref.asInstanceOf[RepointableActorRef].underlying.asInstanceOf[ActorCell].dispatcher.id should ===("akka.stream.default-file-io-dispatcher")
      } finally shutdown(sys)
    }

    "allow overriding the dispatcher using OperationAttributes" in {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val mat = ActorFlowMaterializer()(sys)
      implicit val timeout = Timeout(500.millis)

      try {
        SynchronousFileSource(manyLines)
          .withAttributes(ActorOperationAttributes.dispatcher("akka.actor.default-dispatcher"))
          .runWith(Sink.ignore)(mat)

        val ref = Await.result(sys.actorSelection("/user/$a/flow-*").resolveOne(), timeout.duration)
        ref.asInstanceOf[RepointableActorRef].underlying.asInstanceOf[ActorCell].dispatcher.id should ===("akka.actor.default-dispatcher")
      } finally shutdown(sys)
    }
  }

  override def afterTermination(): Unit = {
    testFile.delete()
    manyLines.delete()
  }

}
