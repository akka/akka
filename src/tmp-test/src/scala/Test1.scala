import akka.actor.{Props, ActorSystem}

import RichString._

object Test1 extends App{
  import akka.actor.Actor
  import java.lang.String
  import akka.camel.Message

  class CamelConsumer extends Actor with akka.camel.Consumer{
    from("file://data/input/CamelConsumer")

    protected def receive = {
      case msg : Message =>{
        printf("Received '%s'\n", msg.bodyAs[String] )
      }
    }
  }

  akka.camel.CamelServiceManager.startCamelService
  ActorSystem("test1").actorOf(Props[CamelConsumer])

  "data/input/CamelConsumer/file1.txt" << "test data 1"

  Thread.sleep(10000)

}