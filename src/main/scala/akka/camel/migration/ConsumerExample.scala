package akka.camel.migration


import RichString._
import akka.camel.Consumer
import java.lang.String
import akka.camel.Message
import akka.util.duration._
import akka.actor.{PoisonPill, Terminated, OneForOneStrategy, Props, ActorSystem, Actor}

object ConsumerExample extends App{

  val system = ActorSystem("test1")

  val retry3xWithin1s = OneForOneStrategy(List(classOf[Exception]), maxNrOfRetries = 3, withinTimeRange = 1000)

  val pappa = {
    system.actorOf(Props(faultHandler = retry3xWithin1s, creator = () => new Actor {
      protected def receive = {
        case props: Props => {
          sender ! context.watch(context.actorOf(props))
        }
        case Terminated(ref) => {
          printf("Hey! One of the endpoints has died: %s. I am doing sepuku...", ref)
          self ! PoisonPill
        }
      }
    }))
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

  system.actorOf(Props(new Actor{
    this.context.watch(pappa)
    protected def receive = {
      case Terminated(ref) => system.shutdown()
    }
  }))

  pappa ! Props[FileConsumer]
  pappa ! Props[TroubleMaker]
    


  "data/input/CamelConsumer/file1.txt" << "test data "+math.random
}