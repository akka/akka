/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream.io

import java.io.File

import akka.stream._
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.util.ByteString

import scala.concurrent.Future

class StreamFileDocSpec extends AkkaSpec(UnboundedMailboxConfig) {

  implicit val ec = system.dispatcher
  implicit val mat = ActorMaterializer()

  // silence sysout
  def println(s: String) = ()

  val file = File.createTempFile(getClass.getName, ".tmp")

  override def afterTermination() = file.delete()

  {
    //#file-source
    import akka.stream.io._
    //#file-source
    Thread.sleep(0) // needs a statement here for valid syntax and to avoid "unused" warnings
  }

  {
    //#file-source
    val file = new File("example.csv")
    //#file-source
  }

  "read data from a file" in {
    //#file-source
    def handle(b: ByteString): Unit //#file-source
    = ()

    //#file-source

    val foreach: Future[Long] = Source.file(file)
      .to(Sink.ignore)
      .run()
    //#file-source
  }

  "configure dispatcher in code" in {
    //#custom-dispatcher-code
    Sink.file(file)
      .withAttributes(ActorAttributes.dispatcher("custom-blocking-io-dispatcher"))
    //#custom-dispatcher-code
  }
}
