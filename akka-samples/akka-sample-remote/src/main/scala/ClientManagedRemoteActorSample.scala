/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.remote

import akka.actor.Actor._
import akka.actor.RemoteActor
import akka.remote.RemoteNode
import akka.util.Logging

class RemoteHelloWorldActor extends RemoteActor("localhost", 2552) {
  def receive = {
    case "Hello" =>
      log.info("Received 'Hello'")
      self.reply("World")
  }
}

object ClientManagedRemoteActorServer extends Logging {
  def run = {
    RemoteNode.start("localhost", 2552)
    log.info("Remote node started")
  }

  def main(args: Array[String]) = run
}

object ClientManagedRemoteActorClient extends Logging {

  def run = {
    val actor = actorOf[RemoteHelloWorldActor].start
    log.info("Remote actor created, moved to the server")
    log.info("Sending 'Hello' to remote actor")
    val result = actor !! "Hello"
    log.info("Result from Remote Actor: '%s'", result.get)
  }

  def main(args: Array[String]) = run
}

