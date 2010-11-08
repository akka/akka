/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.remote

import akka.actor.Actor
import akka.actor.Actor._
import akka.remote.{RemoteClient, RemoteNode}
import akka.util.Logging

class HelloWorldActor extends Actor {
  def receive = {
    case "Hello" =>
      log.info("Received 'Hello'")
      self.reply("World")
  }
}

object ServerManagedRemoteActorServer extends Logging {

  def run = {
    RemoteNode.start("localhost", 2552)
    log.info("Remote node started")
    RemoteNode.register("hello-service", actorOf[HelloWorldActor])
    log.info("Remote actor registered and started")
  }

  def main(args: Array[String]) = run
}

object ServerManagedRemoteActorClient extends Logging {

  def run = {
    val actor = RemoteClient.actorFor("hello-service", "localhost", 2552)
    log.info("Remote client created")
    log.info("Sending 'Hello' to remote actor")
    val result = actor !! "Hello"
    log.info("Result from Remote Actor: '%s'", result.get)
  }

  def main(args: Array[String]) = run
}

