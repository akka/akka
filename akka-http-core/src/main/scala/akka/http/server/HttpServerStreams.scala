package akka.http.server

import scala.concurrent.Future
import java.net.InetSocketAddress
import akka.streams.io.TcpStream
import akka.actor.ActorSystem

object HttpServerStreams {
  // captures the 1 request -> 1 response semantics of HTTP
  type Handler = HttpRequestStream => Future[HttpResponseStream]

  def service(address: InetSocketAddress)(handler: Handler)(implicit system: ActorSystem): Unit = {
    import system.dispatcher
    import akka.streams.Combinators._
    TcpStream.listen(address) foreach {
      case (address, io) ⇒ registerConnectionHandler(handler)(io)
    }
  }

  def registerConnectionHandler(handler: Handler)(io: TcpStream.IOStream): Unit = {
    import akka.streams.Combinators._
    val (requests, responses) = httpConnection(io)
      requests.flatMap { req =>
        handler(req).asProducer
      }.connect(responses)
  }
  def httpConnection(io: TcpStream.IOStream): HttpStream = HttpImplementation(io)
}

