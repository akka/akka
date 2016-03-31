/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.io

import java.io.File
import java.nio.file.StandardOpenOption
import akka.actor.ActorSystem
import akka.stream.impl.ActorMaterializerImpl
import akka.stream.impl.StreamSupervisor
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.scaladsl.{ FileIO, Source }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorAttributes
import akka.util.ByteString
import akka.util.Timeout
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.testkit.AkkaSpec

class FileSinkSpec extends AkkaSpec(UnboundedMailboxConfig) {

  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  val TestLines = {
    val b = ListBuffer[String]()
    b.append("a" * 1000 + "\n")
    b.append("b" * 1000 + "\n")
    b.append("c" * 1000 + "\n")
    b.append("d" * 1000 + "\n")
    b.append("e" * 1000 + "\n")
    b.append("f" * 1000 + "\n")
    b.toList
  }

  val TestByteStrings = TestLines.map(ByteString(_))

  "SynchronousFile Sink" must {
    "write lines to a file" in assertAllStagesStopped {
      targetFile { f ⇒
        val completion = Source(TestByteStrings)
          .runWith(FileIO.toFile(f))

        val result = Await.result(completion, 3.seconds)
        result.count should equal(6006)
        checkFileContents(f, TestLines.mkString(""))
      }
    }

    "create new file if not exists" in assertAllStagesStopped {
      targetFile({ f ⇒
        val completion = Source(TestByteStrings)
          .runWith(FileIO.toFile(f))

        val result = Await.result(completion, 3.seconds)
        result.count should equal(6006)
        checkFileContents(f, TestLines.mkString(""))
      }, create = false)
    }

    "by default write into existing file" in assertAllStagesStopped {
      targetFile { f ⇒
        def write(lines: List[String]) =
          Source(lines)
            .map(ByteString(_))
            .runWith(FileIO.toFile(f))

        val completion1 = write(TestLines)
        Await.result(completion1, 3.seconds)

        val lastWrite = List("x" * 100)
        val completion2 = write(lastWrite)
        val result = Await.result(completion2, 3.seconds)

        result.count should ===(lastWrite.flatten.length)
        checkFileContents(f, lastWrite.mkString("") + TestLines.mkString("").drop(100))
      }
    }

    "allow appending to file" in assertAllStagesStopped {
      targetFile { f ⇒
        def write(lines: List[String] = TestLines) =
          Source(lines)
            .map(ByteString(_))
            .runWith(FileIO.toFile(f, Set(StandardOpenOption.APPEND)))

        val completion1 = write()
        val result1 = Await.result(completion1, 3.seconds)

        val lastWrite = List("x" * 100)
        val completion2 = write(lastWrite)
        val result2 = Await.result(completion2, 3.seconds)

        f.length() should ===(result1.count + result2.count)
        checkFileContents(f, TestLines.mkString("") + lastWrite.mkString("") + "\n")
      }
    }

    "use dedicated blocking-io-dispatcher by default" in assertAllStagesStopped {
      targetFile { f ⇒
        val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
        val materializer = ActorMaterializer()(sys)
        try {
          Source.fromIterator(() ⇒ Iterator.continually(TestByteStrings.head)).runWith(FileIO.toFile(f))(materializer)

          materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
          val ref = expectMsgType[Children].children.find(_.path.toString contains "fileSource").get
          assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
        } finally shutdown(sys)
      }
    }

    // FIXME: overriding dispatcher should be made available with dispatcher alias support in materializer (#17929)
    "allow overriding the dispatcher using Attributes" in assertAllStagesStopped {
      pending
      targetFile { f ⇒
        val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
        val materializer = ActorMaterializer()(sys)
        implicit val timeout = Timeout(3.seconds)

        try {
          Source.fromIterator(() ⇒ Iterator.continually(TestByteStrings.head))
            .to(FileIO.toFile(f))
            .withAttributes(ActorAttributes.dispatcher("akka.actor.default-dispatcher"))
            .run()(materializer)

          materializer.asInstanceOf[ActorMaterializerImpl].supervisor.tell(StreamSupervisor.GetChildren, testActor)
          val ref = expectMsgType[Children].children.find(_.path.toString contains "File").get
          assertDispatcher(ref, "akka.actor.default-dispatcher")
        } finally shutdown(sys)
      }
    }

  }

  private def targetFile(block: File ⇒ Unit, create: Boolean = true) {
    val targetFile = File.createTempFile("synchronous-file-sink", ".tmp")
    if (!create) targetFile.delete()
    try block(targetFile) finally targetFile.delete()
  }

  def checkFileContents(f: File, contents: String): Unit = {
    val s = scala.io.Source.fromFile(f)
    val out = s.getLines().mkString("\n") + "\n"
    s.close()
    out should ===(contents)
  }

}
