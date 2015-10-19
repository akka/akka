package zzz.akka.investigation

// All that's needed for now are three components from Akka

import akka.actor.{Actor, Props, ActorSystem}

// Our Actor
class BadShakespeareanActor extends Actor {
  // The 'Business Logic'
  def receive = {
    case "Good Morning" =>
      println("Him: Forsooth 'tis the 'morn, but mourneth for thou doest I do!")
    case "You're terrible" =>
      println("Him: Yup")
  }

}

object BadShakespeareanMain {
  val system = ActorSystem("BadShakespearean")
  val actor = system.actorOf(Props[BadShakespeareanActor])

  // We'll use this utility method to talk with our Actor
  def send(msg: String) {
    println("Me : " + msg)
    actor ! msg
    Thread.sleep(100)
  }

  // And our driver
  def main(args: Array[String]) {
    send("Good Morning")
    send("You're terrible")
    system.shutdown()
  }

}