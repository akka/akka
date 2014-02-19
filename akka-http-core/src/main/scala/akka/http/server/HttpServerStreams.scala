package akka.http.server

import java.net.InetSocketAddress
import akka.streams.io.TcpStream
import akka.actor.{ Props, Actor, ActorSystem }
import akka.streams._
import Operation._
import akka.http.server.HttpImplementation.{ Parsed, ImmediateResponse, ParsedPartOrResponse }
import spray.http._
import scala.concurrent.Future
import spray.can.server.ServerSettings
import akka.util.{ Timeout, ByteString }
import rx.async.api.Producer
import akka.pattern.ask
import scala.concurrent.duration._
import spray.http.HttpRequest
import akka.http.server.HttpImplementation.Parsed
import spray.http.HttpResponse
import akka.streams.Operation.Map
import akka.http.server.HttpImplementation.ImmediateResponse
import akka.streams.ActorBasedImplementationSettings
import rx.async.spi.Publisher
import java.io.{ FileInputStream, InputStream, File }

sealed trait MaybeStreamedHttpResponse
case class StreamedHttpResponse(headers: HttpResponseHeaders, body: Producer[ByteString]) extends MaybeStreamedHttpResponse
case class ImmediateHttpResponse(response: HttpResponse) extends MaybeStreamedHttpResponse

object HttpServerStreams {
  def httpPartsServer(address: InetSocketAddress)(handler: InetSocketAddress ⇒ Operation[ParsedPartOrResponse, HttpResponsePart])(implicit system: ActorSystem, factory: ImplementationFactory): Unit = {
    //import system.dispatcher
    TcpStream.listen(address).foreach {
      case (address, io) ⇒
        println(s"Got connection from $address")
        HttpImplementation.handleParts(io, ServerSettings(system))(handler(address)).run()
    }.run()
  }
  def httpServer(address: InetSocketAddress)(handler: InetSocketAddress ⇒ HttpRequestStream ⇒ Future[MaybeStreamedHttpResponse])(implicit system: ActorSystem, factory: ImplementationFactory): Unit = {
    //import system.dispatcher
    TcpStream.listen(address).foreach {
      case (address, io) ⇒
        println(s"Got connection from $address")
        HttpImplementation.handleRequests(io, ServerSettings(system))(handler(address)).run()
    }.run()
  }
}

object TestServer extends App {
  implicit val system = ActorSystem()
  implicit val factory = new ActorBasedImplementationFactory(ActorBasedImplementationSettings(system))

  def partsServer() =
    HttpServerStreams.httpPartsServer(new InetSocketAddress("localhost", 8080)) { remote ⇒
      Map[ParsedPartOrResponse, HttpResponsePart] {
        case Parsed(req: HttpRequest) ⇒
          //println(s"Got request $req")
          HttpResponse(entity = s"Got request to ${req.uri} from $remote")
        case ImmediateResponse(res) ⇒ res
      } //.toProcessor()
    }

  def requestServer() =
    HttpServerStreams.httpServer(new InetSocketAddress("localhost", 8080)) { remote ⇒
      {
        case req ⇒
          //val res = HttpResponse(entity = s"Got request to ${headers.uri} from $remote")
          //Future.successful((res.mapHeaders(HttpHeaders.`Content-Length`(res.entity.data.length) :: _), Source.empty[ByteString].toProducer()))
          //Future.successful(ImmediateHttpResponse(res))
          val handler = system.actorOf(Props(new RequestHandler(remote)))
          implicit val timeout = Timeout(3.seconds)
          (handler ? req).mapTo[MaybeStreamedHttpResponse]

      }
    }

  //partsServer()
  requestServer()

  /*Console.readLine()
  system.shutdown()
  system.awaitTermination()
  System.gc()*/
}

class RequestHandler(remote: InetSocketAddress)(implicit factory: ImplementationFactory) extends Actor {
  val bigFile = new File("/home/johannes/Downloads/incoming/2013-09-25-wheezy-raspbian.zip")
  require(bigFile.exists)

  def receive = {
    case (HttpRequest(_, Uri.Path("/stream"), _, _, _), _) ⇒

      sender !
        StreamedHttpResponse(HttpResponse(StatusCodes.OK, headers = List(HttpHeaders.`Content-Length`(bigFile.length()))),
          new FileIterable(bigFile).toSource.toProducer())
    case (HttpRequest(_, uri, _, _, _), body) ⇒ sender ! ImmediateHttpResponse(HttpResponse(StatusCodes.OK, s"Got request to $uri from $remote"))
  }
}