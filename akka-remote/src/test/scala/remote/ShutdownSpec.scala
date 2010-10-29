package akka.remote

import akka.actor.Actor

import Actor._

object ActorShutdownRunner {
  def main(args: Array[String]) {
    class MyActor extends Actor {
      def receive = {
        case "test" => println("received test")
        case m@_ => println("received unknown message " + m)
      }
    }

    val myActor = actorOf[MyActor]
    myActor.start
    myActor ! "test"
    myActor.stop
  }
}


// case 2

object RemoteServerAndClusterShutdownRunner {
  def main(args: Array[String]) {
    val s1 = new RemoteServer
    val s2 = new RemoteServer
    val s3 = new RemoteServer
    s1.start("localhost", 2552)
    s2.start("localhost", 9998)
    s3.start("localhost", 9997)
    Thread.sleep(5000)
    s1.shutdown
    s2.shutdown
    s3.shutdown
  }
}
