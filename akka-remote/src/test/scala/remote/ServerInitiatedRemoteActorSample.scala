package akka.actor.remote

import akka.actor.Actor
import Actor._
import akka.event.EventHandler

/**
 * ***********************************
 * Instructions how to run the sample:
 *
 * Download Akka distribution.
 * Unzip and step into the Akka root dir
 * Set AKKA_HOME. For exampe 'export AKKA_HOME=`pwd`
 *
 * Then open up two shells and in each run:
 * sbt
 * > project akka-remote
 * > console
 *
 * Then paste in the code below into both shells.
 *
 * Then run:
 * ServerInitiatedRemoteActorServer.run() in one shell
 * ServerInitiatedRemoteActorClient.run() in the other shell
 * Have fun.
 * ***********************************
 */

class HelloWorldActor extends Actor {
  def receive = {
    case "Hello" ⇒ self.reply("World")
  }
}

object ServerInitiatedRemoteActorServer {

  def run() {
    remote.start("localhost", 2552)
    remote.register("hello-service", actorOf[HelloWorldActor])
  }

  def main(args: Array[String]) { run() }
}

object ServerInitiatedRemoteActorClient {

  def run() {
    val actor = remote.actorFor("hello-service", "localhost", 2552)
    val result = actor !! "Hello"
    EventHandler.info("Result from Remote Actor: %s", result)
  }

  def main(args: Array[String]) { run() }
}

