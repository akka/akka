package tutorial_1

import akka.actor.{ Actor, Props }
import akka.testkit.AkkaSpec

//#print-refs
class PrintMyActorRefActor extends Actor {
  override def receive: Receive = {
    case "printit" =>
      val secondRef = context.actorOf(Props.empty, "second-actor")
      println(secondRef)
  }
}
//#print-refs

//#start-stop
class StartStopActor1 extends Actor {
  override def receive: Receive = {
    case "stop" => context.stop(self)
  }

  override def preStart(): Unit = {
    println("first started")
    context.actorOf(Props[StartStopActor2], "second")
  }
  override def postStop(): Unit = println("first stopped")
}

class StartStopActor2 extends Actor {
  // Actor.emptyBehavior is a useful placeholder when we don't
  // want to handle any messages in the actor.
  override def receive: Receive = Actor.emptyBehavior

  override def preStart(): Unit = println("second started")
  override def postStop(): Unit = println("second stopped")
}
//#start-stop

//#supervise
class SupervisingActor extends Actor {
  val child = context.actorOf(Props[SupervisedActor], "supervised-actor")

  override def receive: Receive = {
    case "failChild" => child ! "fail"
  }
}

class SupervisedActor extends Actor {

  override def preStart(): Unit = println("supervised actor started")
  override def postStop(): Unit = println("supervised actor stopped")

  override def receive: Receive = {
    case "fail" =>
      println("supervised actor fails now")
      throw new Exception("I failed!")
  }
}
//#supervise

class ActorHierarchyExperiments extends AkkaSpec {

  "create top and child actor" in {

    //#print-refs
    val firstRef = system.actorOf(Props[PrintMyActorRefActor], "first-actor")
    println(firstRef)
    firstRef ! "printit"
    //#print-refs

  }

  "start and stop actors" in {
    //#start-stop
    val first = system.actorOf(Props[StartStopActor1], "first")
    first ! "stop"
    //#start-stop
  }

  "supervise actors" in {
    //#supervise
    val supervisingActor = system.actorOf(Props[SupervisingActor], "supervising-actor")
    supervisingActor ! "failChild"
    //#supervise
  }

}
