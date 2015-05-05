package docs.stream.cookbook

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeParseLines extends RecipeSpec {

  "Recipe for parsing line from bytes" must {

    "work" in {
      val rawData = Source(List(
        ByteString("Hello World"),
        ByteString("\r"),
        ByteString("!\r"),
        ByteString("\nHello Akka!\r\nHello Streams!"),
        ByteString("\r\n\r\n")))

      //#parse-lines
      import akka.stream.io.Framing
      val linesStream = rawData.via(Framing.lines("\r\n", maximumLineBytes = 100))
      //#parse-lines

      Await.result(linesStream.grouped(10).runWith(Sink.head), 3.seconds) should be(List(
        "Hello World\r!",
        "Hello Akka!",
        "Hello Streams!",
        ""))
    }

  }

}
