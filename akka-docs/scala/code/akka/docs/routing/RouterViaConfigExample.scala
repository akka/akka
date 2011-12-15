package akka.docs.routing

import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.{ Actor, Props, ActorSystem }
import akka.routing.RoundRobinRouter

case class Message(nbr: Int)

class ExampleActor extends Actor {
  def receive = {
    case Message(nbr) ⇒ println("Received %s in router %s".format(nbr, self.path.name))
  }
}

object RouterWithConfigExample extends App {
  val system = ActorSystem("Example")
  //#configurableRouting
  val router = system.actorOf(Props[PrintlnActor].withRouter(RoundRobinRouter()),
    "exampleActor")
  //#configurableRouting
  1 to 10 foreach { i ⇒ router ! Message(i) }
}