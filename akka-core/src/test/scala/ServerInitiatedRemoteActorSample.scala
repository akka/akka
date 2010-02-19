package sample

import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.remote.{RemoteClient, RemoteNode}
import se.scalablesolutions.akka.util.Logging

class HelloWorldActor extends Actor {
  start
  def receive = {
    case "Hello" => reply("World")
  }
}

object ServerInitiatedRemoteActorServer {

  def run = {
    RemoteNode.start("localhost", 9999)
    RemoteNode.register("hello-service", new HelloWorldActor)
  }

  def main(args: Array[String]) = run
}

object ServerInitiatedRemoteActorClient extends Logging {
  
  def run = {
    val actor = RemoteClient.actorFor("hello-service", "localhost", 9999)
    val result = actor !! "Hello"
    log.info("Result from Remote Actor: %s", result)
  }

  def main(args: Array[String]) = run
}

