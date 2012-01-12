package akka.camel.migration


import RichString._
import java.lang.String
import akka.util.duration._
import akka.actor.{Terminated, OneForOneStrategy, Props, ActorSystem, Actor}
import akka.camel.{Consumer, Message}

object ConsumerExample extends App{



  class EndpointManager extends Actor {

      override def preStart() {
        self ! Props[FileConsumer]
        self ! Props[TroubleMaker]
      }

      protected def receive = {
        case props: Props => {
          sender ! context.watch(context.actorOf(props))
        }
        case Terminated(ref) => {
          printf("Hey! One of the endpoints has died: %s. I am doing sepuku...\n", ref)
          context.system.stop(self)
        }
      }
    }

  class FileConsumer extends Consumer{
    override def activationTimeout = 10 seconds
    def endpointUri = "file://data/input/CamelConsumer"

    protected def receive = {
      case msg : Message =>{
        printf("Received '%s'\n", msg.bodyAs[String] )
      }
    }
  }

  class TroubleMaker extends  Consumer{
    println("Trying to instantiate conumer with uri: "+endpointUri)
    protected def receive = {case _ => }
    def endpointUri = "WRONG URI"
  }

  val retry3xWithin1s = OneForOneStrategy(List(classOf[Exception]), maxNrOfRetries = 3, withinTimeRange = 1000)
  val system = ActorSystem("test1")

  system.actorOf(Props(new Actor{
    context.watch(context.actorOf(Props(faultHandler = retry3xWithin1s, creator = () => new EndpointManager)))
    protected def receive = {
      case Terminated(ref) => system.shutdown()
    }
  }))


  "data/input/CamelConsumer/file1.txt" << "test data "+math.random
}