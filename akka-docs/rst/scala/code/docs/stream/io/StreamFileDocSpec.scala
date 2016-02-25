/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream.io

import java.io.File

import akka.stream._
import akka.stream.scaladsl.{ FileIO, Sink, Source }
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.util.ByteString
import akka.testkit.AkkaSpec

import scala.concurrent.Future

class StreamFileDocSpec extends AkkaSpec(UnboundedMailboxConfig) {

  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  // silence sysout
  def println(s: String) = ()

  val file = File.createTempFile(getClass.getName, ".tmp")

  override def afterTermination() = file.delete()

  {
    //#file-source
    import akka.stream.scaladsl._
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

    val foreach: Future[IOResult] = FileIO.fromFile(file)
      .to(Sink.ignore)
      .run()
    //#file-source
  }

  "configure dispatcher in code" in {
    //#custom-dispatcher-code
    FileIO.fromFile(file)
      .withAttributes(ActorAttributes.dispatcher("custom-blocking-io-dispatcher"))
    //#custom-dispatcher-code
  }
}
