package akka.camel.migration

import akka.actor.{Props, ActorSystem}

import RichString._

object Test1 extends App{
  import akka.actor.Actor
  import java.lang.String
  import akka.camel.Message

  val system = ActorSystem("test1")

  val killer = system.actorOf(Props(new Actor{
    protected def receive = {
      case "stop" => {
        system.shutdown()
      }
    }
  }))

  class CamelConsumer extends Actor with akka.camel.Consumer{

    def endpointUri = "file://data/input/CamelConsumer"

    protected def receive = {
      case msg : Message =>{
        printf("Received '%s'\n", msg.bodyAs[String] )
        killer ! "stop"
      }
    }
  }



  system.actorOf(Props[CamelConsumer])

  "data/input/CamelConsumer/file1.txt" << "test data "+math.random
}