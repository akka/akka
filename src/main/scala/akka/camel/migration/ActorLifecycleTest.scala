package akka.camel.migration

import akka.actor.{AllForOneStrategy, Actor, Props, ActorSystem}


object  ActorLifecycleTest extends App{

  val system = ActorSystem("Test")
  val supervisor = system.actorOf(Props(faultHandler = AllForOneStrategy(List(classOf[Exception]), maxNrOfRetries = 3, withinTimeRange = 10000), creator = () => new Actor {
      protected def receive = {
        case creator: (() => Actor) => context.actorOf(Props(creator))
        case x => println("Got: " + x)
      }
    }), "Supervisor")


  val troubleMaker = () => new Actor {
          Thread.sleep(300)
          println("Instatiating TroubleMaker")
          throw new RuntimeException("TroubleMaker")
          protected def receive = null
        }

  println("post actorOf")

  supervisor ! "test"
  supervisor ! troubleMaker
}