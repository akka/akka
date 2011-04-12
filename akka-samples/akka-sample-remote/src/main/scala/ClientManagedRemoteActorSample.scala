/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.remote

import akka.actor.Actor._
import akka.actor. {ActorRegistry, Actor}
import Actor.remote

class RemoteHelloWorldActor extends Actor {
  def receive = {
    case "Hello" =>
      self.reply("World")
  }
}

object ClientManagedRemoteActorServer {
  def run = {
    remote.start("localhost", 2552)
  }

  def main(args: Array[String]) = run
}

object ClientManagedRemoteActorClient {

  def run = {
    val actor = remote.actorOf[RemoteHelloWorldActor]("localhost",2552).start()
    val result = actor !! "Hello"
  }

  def main(args: Array[String]) = run
}

