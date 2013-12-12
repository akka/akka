package sample.camel

import akka.actor.ActorSystem
import akka.actor.Props
import akka.camel.Consumer

object QuartzExample {

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("my-quartz-system")
    system.actorOf(Props[MyQuartzActor])
  }

  class MyQuartzActor extends Consumer {

    def endpointUri = "quartz://example?cron=0/2+*+*+*+*+?"

    def receive = {

      case msg => println("==============> received %s " format msg)

    }

  }

}
