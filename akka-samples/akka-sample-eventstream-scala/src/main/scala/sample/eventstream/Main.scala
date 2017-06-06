package sample.eventstream

import akka.actor.ActorSystem
import akka.actor.Props

object DeathWatch {
  def main(args: Array[String]): Unit = {

    val system = ActorSystem("system")
    val mainActor = system.actorOf(Props[ParentActor], "parent")
    Thread.sleep(5000)
    system.shutdown()
  }
}
