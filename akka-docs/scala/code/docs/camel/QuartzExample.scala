package docs.camel


object QuartzExample {

  {
    //#Quartz
    import akka.actor.{ActorSystem, Props}

    import akka.camel.{Consumer}

    class MyQuartzActor extends Consumer {

      def endpointUri = "quartz://example?cron=0/2+*+*+*+*+?"

      def receive = {

        case msg => println("==============> received %s " format msg)

      } // end receive

    } // end MyQuartzActor

    object MyQuartzActor {

      def main(str: Array[String]) {
        val system = ActorSystem("my-quartz-system")
        system.actorOf(Props[MyQuartzActor])
      } // end main

    } // end MyQuartzActor
    //#Quartz
  }

}
