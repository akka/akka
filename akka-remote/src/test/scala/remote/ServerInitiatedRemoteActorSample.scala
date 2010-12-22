package akka.actor.remote

import akka.actor.Actor
import akka.remote.{RemoteClient, RemoteNode}
import akka.util.Logging

import Actor._

/*************************************
Instructions how to run the sample:

* Download Akka distribution.
* Unzip and step into the Akka root dir
* Set AKKA_HOME. For exampe 'export AKKA_HOME=`pwd`

* Then open up two shells and in each run:
* sbt
* > project akka-remote
* > console

* Then paste in the code below into both shells.

Then run:
* ServerInitiatedRemoteActorServer.run in one shell
* ServerInitiatedRemoteActorClient.run in one shell
Have fun.
*************************************/

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

