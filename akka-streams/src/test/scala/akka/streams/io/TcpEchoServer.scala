package akka.streams.io

import akka.actor.ActorSystem
import java.net.InetSocketAddress
import akka.util.ByteString
import akka.streams._

object TcpEchoServer {
  def main(args: Array[String]) {
    implicit val system = ActorSystem("tcpdemo")
    implicit val genny = new ActorBasedStreamGenerator(ActorBasedStreamGeneratorSettings(system))
    TcpStream.listen(new InetSocketAddress("localhost", 1111)).foreach {
      case (address, (in, out)) ⇒
        println(s"Client connected: $address")
        in.map(ByteString("Hello ") ++ _).connectTo(out).run()
    }.run()
    println("Echo server started, type RETURN to exit.")
    Console.readLine()
    system.shutdown()
  }
}
