package akka.camel.migration

import akka.actor.{ActorRef, Terminated, AllForOneStrategy, Actor, Props, ActorSystem}


object  ActorLifecycleTest extends App{

  val system = ActorSystem("Test")
  val supervisor = system.actorOf(Props(faultHandler = AllForOneStrategy(List(classOf[Exception]), maxNrOfRetries = 3, withinTimeRange = 10000), creator = () => new Actor {
      protected def receive = {
        case ("watch", actor : ActorRef) => context.watch(actor)
        case props: Props => context.actorOf(props)
        case Terminated(actor) => println("Terminated "+actor)
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

  val dead = system.actorOf(Props(ctx => {
    case _ => ctx.system.stop(ctx.self)
  }))

  dead ! "kill"
  while (!dead.isTerminated) Thread.sleep(1000)
  supervisor ! ("watch", dead)

//  supervisor ! "test"
//  supervisor ! Props(troubleMaker)


}