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

      import RecipeParseLines._

      val linesStream = rawData.transform(() => parseLines("\r\n", 100))

      Await.result(linesStream.grouped(10).runWith(Sink.head), 3.seconds) should be(List(
        "Hello World\r!",
        "Hello Akka!",
        "Hello Streams!",
        ""))
    }

  }

}

object RecipeParseLines {

  import akka.stream.stage._

  //#parse-lines
  def parseLines(separator: String, maximumLineBytes: Int) =
    new StatefulStage[ByteString, String] {
      private val separatorBytes = ByteString(separator)
      private val firstSeparatorByte = separatorBytes.head
      private var buffer = ByteString.empty
      private var nextPossibleMatch = 0

      def initial = new State {
        override def onPush(chunk: ByteString, ctx: Context[String]): SyncDirective = {
          buffer ++= chunk
          if (buffer.size > maximumLineBytes)
            ctx.fail(new IllegalStateException(s"Read ${buffer.size} bytes " +
              s"which is more than $maximumLineBytes without seeing a line terminator"))
          else emit(doParse(Vector.empty).iterator, ctx)
        }

        @tailrec
        private def doParse(parsedLinesSoFar: Vector[String]): Vector[String] = {
          val possibleMatchPos = buffer.indexOf(firstSeparatorByte, from = nextPossibleMatch)
          if (possibleMatchPos == -1) {
            // No matching character, we need to accumulate more bytes into the buffer
            nextPossibleMatch = buffer.size
            parsedLinesSoFar
          } else if (possibleMatchPos + separatorBytes.size > buffer.size) {
            // We have found a possible match (we found the first character of the terminator
            // sequence) but we don't have yet enough bytes. We remember the position to
            // retry from next time.
            nextPossibleMatch = possibleMatchPos
            parsedLinesSoFar
          } else {
            if (buffer.slice(possibleMatchPos, possibleMatchPos + separatorBytes.size)
              == separatorBytes) {
              // Found a match
              val parsedLine = buffer.slice(0, possibleMatchPos).utf8String
              buffer = buffer.drop(possibleMatchPos + separatorBytes.size)
              nextPossibleMatch -= possibleMatchPos + separatorBytes.size
              doParse(parsedLinesSoFar :+ parsedLine)
            } else {
              nextPossibleMatch += 1
              doParse(parsedLinesSoFar)
            }
          }

        }
      }
    }
  //#parse-lines

}
