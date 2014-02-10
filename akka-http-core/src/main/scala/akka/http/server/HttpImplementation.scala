package akka.http.server

import akka.streams.io.TcpStream
import rx.async.api.{Consumer, Producer}
import akka.util.ByteString
import spray.http._
import scala.concurrent.Future
import akka.streams.Combinators._
import spray.http.ChunkedResponseStart


object HttpImplementation {
  type Stage[I, O] = Producer[I] => Producer[O]

  def apply(io: TcpStream.IOStream): HttpStream = ???

/*  def asParts(io: TcpStream.IOStream): HttpPartStream = {
    import akka.streams.Combinators._

    val (in, out) = io

    val (httpIn, directResponses) = parse(in).splice {
      case Parsed(request) => Left(request)
      case ShortcutResponse(response) => Right(response)
    }
    val httpOut = subject[HttpResponsePart]()

    render(httpOut).connect(out)
    (httpIn, httpOut)
  }*/
  def handleParts(io: TcpStream.IOStream)(handler: Producer[HttpRequestPart] => Producer[HttpResponsePart]): Unit = {
    val (in, out) = io

    val responses = parse(in).flatMap {
      case ParsedRequest(request) => handler(request)
      case ImmediateResponse(response) => response
    }
    render(responses).connect(out)
  }
  def handleRequests(io: TcpStream.IOStream)(handler: HttpRequestStream => Future[HttpResponseStream]): Unit = {
    val (in, out) = io
    val h = liftHandler(handler)

    val responses = parse(in).flatMap {
      case ParsedRequest(request) => toParts(h(collect(request)))
      case ImmediateResponse(response) => response
    }
    render(responses).connect(out)
  }

  sealed trait ParsedOrShortcutResponse
  case class ParsedRequest(request: Producer[HttpRequestPart]) extends ParsedOrShortcutResponse
  case class ImmediateResponse(response: Producer[HttpResponsePart]) extends ParsedOrShortcutResponse

  def parse: Stage[ByteString, ParsedOrShortcutResponse] = ??? // should also be implementable as a state machine
  def render: Stage[HttpResponsePart, ByteString] = ??? // can be probably be implemented by map alone

/*  object CollectingStateMachine extends StateMachine[HttpRequestPart, HttpRequestStream] {
    // semantics:
    // state: no-request
    //  case HttpRequest => emit(HttpResponseStream with static body data producer)
    //  case ChunkedRequestStart => emit(HttpResponseStream) -> goto "in-request"(bodySubject)
    // state: in-request(bodySubject)
    //  case MessageChunk => emit bodyBytes to bodySubject
    //  case ChunkedMessageEnd => complete bodySubject -> goto "no-request"

    def initial = noRequest

    def noRequest: State = {
      //TODO: case req: HttpRequest =>
      case ChunkedRequestStart(req) => {
        val bodySubject = Subject[ByteString]()
        Emit((req, bodySubject), inRequest(bodySubject))
      }
    }
    def inRequest(bodySubject: Subject[ByteString]): State = {
      case MessageChunk(data, "") => Do(bodySubject.onNext(data.toByteString), inRequest(bodySubject))
      case ChunkedMessageEnd => Do(bodySubject.onComplete(), noRequest)
    }
  }*/

  def collect: Stage[HttpRequestPart, HttpRequestStream] =
    _.span(_.isInstanceOf[HttpMessageEnd]).flatMap { elements =>
      elements.headTail.map {
        case (ChunkedRequestStart(req), rest) =>
          val bodyParts = rest.takeWhile(_.isInstanceOf[MessageChunk]).map {
            case MessageChunk(data, "") => data.toByteString
          }
          (req, bodyParts)
      }
      // alternative using `first` and `rest`
      /*elements.first.map {
        case ChunkedRequestStart(req) =>
          val rest = elements.rest
          val bodyParts = rest.takeWhile(_.isInstanceOf[MessageChunk]).map {
            case MessageChunk(data, "") => data.toByteString
          }
          (req, bodyParts)
      }*/
    }

  def toParts: Stage[HttpResponseStream, HttpResponsePart] =
    _.flatMap {
      case (headers, body) =>
        Producer(ChunkedResponseStart(headers))
          .andThen[HttpResponsePart](body.map(bytes => MessageChunk(HttpData(bytes))))
          .andThen(Producer(ChunkedMessageEnd))
    }

  def liftHandler(handler: HttpRequestStream => Future[HttpResponseStream]): Stage[HttpRequestStream, HttpResponseStream] = ???
  def Producer[T](single: T): Producer[T] = ???

  trait StateMachine[I, O] extends Stage[I, O] {
    sealed trait Result
    case class Emit(value: O, next: I => Result) extends Result
    case class SideEffect(sideEffect: () => Unit, next: I => Result) extends Result

    type State = PartialFunction[I, Result]

    def Do(sideEffect: => Unit, next: I => Result): Result = SideEffect(sideEffect _, next)

    def initial: State

    def apply(v1: Producer[I]): Producer[O] = ???
  }
}
