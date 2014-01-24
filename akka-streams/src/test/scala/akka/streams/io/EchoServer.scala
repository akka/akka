package akka.streams.io

import akka.actor.ActorSystem
import java.net.InetSocketAddress

object TcpEchoServer {
  import akka.streams.Combinators._
  import scala.concurrent.ExecutionContext.Implicits.global

  def main(args: Array[String]) {
    implicit val system = ActorSystem("tcpdemo")
    /*TcpStream.listen(new InetSocketAddress("localhost", 1111)) foreach {
      case (address, (in, out)) ⇒
        println(s"Client connected: $address")
        in /*.onComplete { println(s"Client disconnected $address") }*/ .getPublisher.subscribe(out.getSubscriber)
    }*/
    // alternative
    TcpStream.listenAndHandle(new InetSocketAddress("localhost", 1111)) { peer ⇒
      identity
    }

    println("Echo server started, type RETURN to exit.")
    //(Std.Console.until(_ == "exit") onComplete system.shutdown()).foreach(println) // Poor mans echo...
    Console.readLine()
    system.shutdown()
  }
}
