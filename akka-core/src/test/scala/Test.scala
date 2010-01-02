package test

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.remote.RemoteNode

object AkkaTest1 {
  def main(args: Array[String]) {

    class MyActor extends Actor {
      def receive = {
        case "test" => println("received test")
        case m@_ => println("received unknown message " + m)
      }
    }

    val myActor = new MyActor
    myActor.start
    myActor.send("test")
    myActor.stop
    // does not exit
  }
}


// case 2

object AkkaTest2 {
  def main(args: Array[String]) {
    RemoteNode.start("localhost", 9999)
    Thread.sleep(3000)
    RemoteNode.shutdown
    // does not exit
  }
}