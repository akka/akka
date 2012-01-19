package akka.camelexamples

import akka.actor.{PoisonPill, Terminated, Props, ActorSystem, Actor}
import ExamplesSupport._
import RichString._


object SupervisedConsumersExample extends App{

  val system = ActorSystem("test1")

  system.actorOf(Props(new Actor{
    context.watch(context.actorOf(Props[EndpointManager].withFaultHandler(retry3xWithin1s)))
    protected def receive = {
      case Terminated(ref) => system.shutdown()
    }
  }))


  "data/input/CamelConsumer/file1.txt" << "test data "+math.random
}

class EndpointManager extends Actor {

  override def preStart() {
    self ! Props[SysOutConsumer]
    self ! Props[TroubleMaker]
  }

  protected def receive = {
    case props: Props => sender ! context.watch(context.actorOf(props))
    case Terminated(ref) => {
      printf("Hey! One of the endpoints has died: %s. I am doing sepuku...\n", ref)
      self ! PoisonPill
    }
  }
}
