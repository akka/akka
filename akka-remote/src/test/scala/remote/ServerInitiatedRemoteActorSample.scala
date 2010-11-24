package akka.actor.remote

import akka.actor.Actor
import akka.remote.{RemoteClient, RemoteNode}
import akka.util.Logging

import Actor._

class HelloWorldActor extends Actor {
  self.start

  def receive = {
    case "Hello" => self.reply("World")
  }
}

object ServerInitiatedRemoteActorServer {

  def run = {
    RemoteNode.start("localhost", 2552)
    RemoteNode.register("hello-service", actorOf[HelloWorldActor])
  }

  def main(args: Array[String]) = run
}

object ServerInitiatedRemoteActorClient extends Logging {

  def run = {
    val actor = RemoteClient.actorFor("hello-service", "localhost", 2552)
    val result = actor !! "Hello"
    log.slf4j.info("Result from Remote Actor: {}", result)
  }

  def main(args: Array[String]) = run
}

