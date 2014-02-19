package spray.can

import spray.can.parsing.{ Result, HttpRequestPartParser }
import spray.can.server.ServerSettings
import akka.actor.ActorSystem
import akka.util.ByteString
import akka.streams.Operation.Process
import akka.http.server.HttpImplementation.{ Parsed, ParsedPartOrResponse }
import scala.annotation.tailrec
import spray.http.{ HttpRequestPart, HttpRequest, ChunkedRequestStart }

class Parser(settings: ServerSettings) {
  type Parser = parsing.Parser
  type Command = Process.Command[ParsedPartOrResponse, Parser]

  val https = false
  def rootParser: Parser = new HttpRequestPartParser(settings.parserSettings, settings.rawRequestUriHeader)()

  import Process._
  def onNext(parser: Parser, data: ByteString): Command = {
    //println(s"Got '${data.decodeString("utf8")}'")
    val result = parser(data)
    val cmd = handleParsingResult(result, identity)
    //println(s"Result of handling '${data.decodeString("utf8")}' is $result => $cmd")
    cmd
  }

  @tailrec final def handleParsingResult(result: Result, cont: Command ⇒ Command): Command =
    result match {
      case Result.NeedMoreData(next) ⇒ cont(Continue(next)) // wait for the next packet
      case Result.Emit(part, closeAfterResponseCompletion, continue) ⇒
        val event = part match {
          case x: HttpRequest         ⇒ Parsed(normalize(x))
          case x: ChunkedRequestStart ⇒ Parsed(ChunkedRequestStart(normalize(x.request)))
          case x: HttpRequestPart     ⇒ Parsed(x)
        }
        handleParsingResult(continue(), next ⇒ cont(Emit(event, next)))

      /*case Result.Expect100Continue(continue) ⇒
        commandPL(Status100ContinueResponse)
        handleParsingResult(continue())

      case e @ Result.ParsingError(status, info) ⇒ handleError(status, info)*/
      case Result.IgnoreAllFurtherInput ⇒ ???
    }
  def normalize(req: HttpRequest) = req.withEffectiveUri(https, settings.defaultHostHeader)

  def onComplete(parser: Parser): Seq[ParsedPartOrResponse] = Nil
}
