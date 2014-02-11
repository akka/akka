package akka.http.server

import java.net.InetSocketAddress
import akka.streams.io.TcpStream
import akka.actor.ActorSystem
import akka.streams._
import Operation._
import akka.streams.impl.RaceTrack
import rx.async.api.Processor
import akka.http.server.HttpImplementation.{ImmediateResponse, ParsedRequestPart, ParsedPartOrResponse}
import spray.http.{HttpResponse, HttpRequest, HttpResponsePart}

object HttpServerStreams {
  /*
  // captures the 1 request -> 1 response semantics of HTTP
  type Handler = HttpRequestStream => Future[HttpResponseStream]

  implicit def settings(implicit system: ActorSystem): ProcessorSettings =
    ProcessorSettings(system, () => new RaceTrack(1))

  def service(address: InetSocketAddress)(handler: Handler)(implicit system: ActorSystem): Unit = {
    import system.dispatcher
    TcpStream.listen(address).foreach {
      case (address, io) ⇒ registerConnectionHandler(handler)(io)
    }.run()
  }

  def registerConnectionHandler(handler: Handler)(io: TcpStream.IOStream)(implicit system: ActorSystem): Unit = {
    val (requests, responses) = httpConnection(io)
      requests.flatMap { req =>
        handler(req).asProducer
      }.finish(responses).run()
  }
  def httpConnection(io: TcpStream.IOStream): HttpStream = HttpImplementation(io)*/

  def httpServer(address: InetSocketAddress)(handler: InetSocketAddress => Processor[ParsedPartOrResponse, HttpResponsePart])(implicit system: ActorSystem, settings: ProcessorSettings): Unit = {
    import system.dispatcher
    producerOps1(TcpStream.listen(address)).foreach {
      case (address, io) ⇒
        println(s"Got connection from $address")
        HttpImplementation.handleParts(io)(handler(address)).run()
    }.run()
  }
}

object TestServer extends App {
  implicit val system = ActorSystem()
  implicit val settings = ProcessorSettings(system, () => new RaceTrack(1))

  HttpServerStreams.httpServer(new InetSocketAddress("localhost", 8080)) { remote =>
    Map[ParsedPartOrResponse, HttpResponsePart] {
      case ParsedRequestPart(req: HttpRequest) =>
        //println(s"Got request $req")
        HttpResponse(entity = s"Got request to ${req.uri} from $remote")
      case ImmediateResponse(res) => res
    }.run()
  }

  Console.readLine()
  system.shutdown()
}
