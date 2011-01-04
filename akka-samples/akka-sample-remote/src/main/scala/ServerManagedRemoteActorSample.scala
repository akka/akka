/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.remote

import akka.actor.Actor._
import akka.util.Logging
import akka.actor. {ActorRegistry, Actor}

class HelloWorldActor extends Actor {
  def receive = {
    case "Hello" =>
      log.slf4j.info("Received 'Hello'")
      self.reply("World")
  }
}

object ServerManagedRemoteActorServer extends Logging {

  def run = {
    Actor.remote.start("localhost", 2552)
    log.slf4j.info("Remote node started")
    Actor.remote.register("hello-service", actorOf[HelloWorldActor])
    log.slf4j.info("Remote actor registered and started")
  }

  def main(args: Array[String]) = run
}

object ServerManagedRemoteActorClient extends Logging {

  def run = {
    val actor = Actor.remote.actorFor("hello-service", "localhost", 2552)
    log.slf4j.info("Remote client created")
    log.slf4j.info("Sending 'Hello' to remote actor")
    val result = actor !! "Hello"
    log.slf4j.info("Result from Remote Actor: '{}'", result.get)
  }

  def main(args: Array[String]) = run
}

