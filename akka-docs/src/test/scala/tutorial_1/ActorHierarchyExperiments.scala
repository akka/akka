package tutorial_1

import akka.testkit.AkkaSpec
//#print-refs
import akka.actor.{ Actor, Props, ActorSystem }
import scala.io.StdIn

class PrintMyActorRefActor extends Actor {
  override def receive: Receive = {
    case "printit" =>
      val secondRef = context.actorOf(Props.empty, "second-actor")
      println(s"Second: $secondRef")
  }
}
//#print-refs

//#start-stop
class StartStopActor1 extends Actor {
  override def preStart(): Unit = {
    println("first started")
    context.actorOf(Props[StartStopActor2], "second")
  }
  override def postStop(): Unit = println("first stopped")

  override def receive: Receive = {
    case "stop" => context.stop(self)
  }
}

class StartStopActor2 extends Actor {
  override def preStart(): Unit = println("second started")
  override def postStop(): Unit = println("second stopped")

  // Actor.emptyBehavior is a useful placeholder when we don't
  // want to handle any messages in the actor.
  override def receive: Receive = Actor.emptyBehavior
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
    // format: OFF
    //#print-refs

object ActorHierarchyExperiments extends App {
  val system = ActorSystem()

  val firstRef = system.actorOf(Props[PrintMyActorRefActor], "first-actor")
  println(s"First : $firstRef")
  firstRef ! "printit"

  println(">>> Press ENTER to exit <<<")
  try StdIn.readLine()
  finally system.terminate()
}
    //#print-refs
    // format: ON
  }

  "start and stop actors" in {
    // format: OFF
    //#start-stop

val first = system.actorOf(Props[StartStopActor1], "first")
first ! "stop"
    //#start-stop
    // format: ON
  }

  "supervise actors" in {
    // format: OFF
    //#supervise

val supervisingActor = system.actorOf(Props[SupervisingActor], "supervising-actor")
supervisingActor ! "failChild"
    //#supervise
    // format: ON
  }
}
