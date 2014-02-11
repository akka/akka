package spray.can

import spray.can.parsing.{Result, HttpRequestPartParser}
import spray.can.server.ServerSettings
import akka.actor.ActorSystem
import akka.util.ByteString
import akka.streams.Operation.UserOperation
import akka.http.server.HttpImplementation.{ParsedRequestPart, ParsedPartOrResponse}
import scala.annotation.tailrec
import spray.http.{HttpRequestPart, HttpMessagePart, HttpRequest, ChunkedRequestStart}

class Parser(implicit system: ActorSystem) {
  type Parser = parsing.Parser
  type Command = UserOperation.Command[ParsedPartOrResponse, Parser]

  val settings = ServerSettings(system)
  val https = false
  def rootParser: Parser = new HttpRequestPartParser(settings.parserSettings, settings.rawRequestUriHeader)()

  import UserOperation._
  def onNext(parser: Parser, data: ByteString): Command = {
    val result = parser(data)
    val cmd = handleParsingResult(result, Continue(parser))
    //println(s"Result of handling $data is $result => $cmd")
    cmd
  }

  @tailrec final def handleParsingResult(result: Result, resultCommand: Command): Command =
    result match {
      case Result.NeedMoreData(next) ⇒ resultCommand ~ Continue(next) // wait for the next packet
      case Result.Emit(part, closeAfterResponseCompletion, continue) ⇒
        val event = part match {
          case x: HttpRequest         ⇒ ParsedRequestPart(normalize(x))
          case x: ChunkedRequestStart ⇒ ParsedRequestPart(ChunkedRequestStart(normalize(x.request)))
          case x: HttpRequestPart                      ⇒ ParsedRequestPart(x)
        }
        handleParsingResult(continue(), resultCommand ~ Emit(event))

      /*case Result.Expect100Continue(continue) ⇒
        commandPL(Status100ContinueResponse)
        handleParsingResult(continue())

      case e @ Result.ParsingError(status, info) ⇒ handleError(status, info)*/
      case Result.IgnoreAllFurtherInput          ⇒ resultCommand
    }
  def normalize(req: HttpRequest) = req.withEffectiveUri(https, settings.defaultHostHeader)

  def onComplete(parser: Parser): Seq[ParsedPartOrResponse] = Nil
}
