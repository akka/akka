//#full-example
package quickstart

import akka.actor.{ Actor, ActorRef, Props, ActorSystem }
import scala.io.StdIn

object HelloWorldApp {
  def main(args: Array[String]): Unit = {
    //#create-send
    val system = ActorSystem("hello-world-actor-system")
    try {
      // Create hello world actor
      val helloWorldActor: ActorRef = system.actorOf(Props[HelloWorldActor], "HelloWorldActor")
      // Send message to actor
      helloWorldActor ! "World"
      // Exit the system after ENTER is pressed
      StdIn.readLine()
    } finally {
      system.terminate()
    }
    //#create-send
  }
}

//#actor-impl
class HelloWorldActor extends Actor {
  def receive = {
    case msg: String => println(s"Hello $msg")
  }
}
//#actor-impl
//#full-example
