package akka.actor.remote

import akka.actor.{Actor, ActorRegistry}
import akka.util.Logging

import Actor._

class HelloWorldActor extends Actor {
  self.start

  def receive = {
    case "Hello" => self.reply("World")
  }
}

object ServerInitiatedRemoteActorServer {

  def main(args: Array[String]) = {
    ActorRegistry.remote.start("localhost", 2552)
    ActorRegistry.remote.register("hello-service", actorOf[HelloWorldActor])
  }
}

object ServerInitiatedRemoteActorClient extends Logging {
  def main(args: Array[String]) = {
    val actor = ActorRegistry.remote.actorFor("hello-service", "localhost", 2552)
    val result = actor !! "Hello"
    log.slf4j.info("Result from Remote Actor: {}", result)
  }
}

