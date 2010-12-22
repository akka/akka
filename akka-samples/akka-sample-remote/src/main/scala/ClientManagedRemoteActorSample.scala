/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.remote

import akka.actor.Actor._
import akka.actor.RemoteActor
import akka.remote.RemoteNode
import akka.util.Logging

class RemoteHelloWorldActor extends RemoteActor("localhost", 2552) {
  def receive = {
    case "Hello" =>
      log.slf4j.info("Received 'Hello'")
      self.reply("World")
  }
}

object ClientManagedRemoteActorServer extends Logging {
  def run = {
    RemoteNode.start("localhost", 2552)
    log.slf4j.info("Remote node started")
  }

  def main(args: Array[String]) = run
}

object ClientManagedRemoteActorClient extends Logging {

  def run = {
    val actor = actorOf[RemoteHelloWorldActor].start
    log.slf4j.info("Remote actor created, moved to the server")
    log.slf4j.info("Sending 'Hello' to remote actor")
    val result = actor !! "Hello"
    log.slf4j.info("Result from Remote Actor: '{}'", result.get)
  }

  def main(args: Array[String]) = run
}

