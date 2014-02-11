package akka.http.server

import akka.streams.io.TcpStream
import rx.async.api.Processor
import akka.util.ByteString
import spray.http._
import scala.concurrent.Future
import akka.streams._
import Operation._
import spray.http.ChunkedResponseStart
import spray.can
import spray.can.Parser
import akka.actor.ActorSystem


object HttpImplementation {
  type Stage[I, O] = Operation[I, O]

  def apply(io: TcpStream.IOStream): HttpStream = ???

  def handleParts(io: TcpStream.IOStream)(handler: Processor[ParsedPartOrResponse, HttpResponsePart])(implicit system: ActorSystem, settings: ProcessorSettings): Pipeline[_] = {
    val (in, out) = io

    in.andThen(parse(new Parser)).andThen(handler).andThen(render).finish(out)
  }

  /*def handleRequests(io: TcpStream.IOStream)(handler: HttpRequestStream => Future[HttpResponseStream]): Unit = {
    val (in, out) = io
    val h = liftHandler(handler)

    val responses = parse(in).flatMap {
      case ParsedRequest(request) => toParts(h(collect(request)))
      case ImmediateResponse(response) => response
    }
    render(responses).connect(out)
  }*/

  sealed trait ParsedPartOrResponse
  case class ParsedRequestPart(part: HttpRequestPart) extends ParsedPartOrResponse
  case class ImmediateResponse(response: HttpResponse) extends ParsedPartOrResponse

  def parse(parser: Parser): Stage[ByteString, ParsedPartOrResponse] =
    UserOperation[ByteString, ParsedPartOrResponse, parser.Parser](parser.rootParser, parser.onNext(_, _), parser.onComplete)

  def render: Stage[HttpResponsePart, ByteString] =
    Identity[HttpResponsePart]().map(can.Renderer.renderPart)

  def collect: Stage[HttpRequestPart, HttpRequestStream] =
    Identity[HttpRequestPart]().span(_.isEnd).flatMap { elements =>
      /*elements.headTail.map {
        case (ChunkedRequestStart(req), rest) =>
          val bodyParts = rest.takeWhile(_.isInstanceOf[MessageChunk]).map {
            case MessageChunk(data, "") => data.toByteString
          }
          (req, bodyParts)
      }*/
      // alternative using `head` and `rest`
      elements.head.map {
        case ChunkedRequestStart(req) =>
          val rest = elements.tail
          val bodyParts = rest.takeWhile(_.isChunk).map {
            case MessageChunk(data, "") => data.toByteString
          }
          //TODO: how can we fix this? (req, bodyParts)
        ???
      }
    }

  def toParts: Stage[HttpResponseStream, HttpResponsePart] =
    Identity[HttpResponseStream]().flatMap {
      case (headers, body) =>
        Source[HttpResponsePart](ChunkedResponseStart(headers)) ++
          FromProducerSource(body).map(bytes => MessageChunk(HttpData(bytes))) ++
          Source(ChunkedMessageEnd)
    }

  implicit class RichHttpRequestPart(val part: HttpRequestPart) extends AnyVal {
    def isEnd: Boolean = part.isInstanceOf[HttpMessageEnd]
    def isChunk: Boolean = part.isInstanceOf[MessageChunk]
  }

  def Source[T](single: T): Source[T] = FromIterableSource(Seq(single))
}
