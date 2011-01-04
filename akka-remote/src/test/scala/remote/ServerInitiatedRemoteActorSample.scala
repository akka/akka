package akka.actor.remote

import akka.actor.{Actor, ActorRegistry}
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

  def main(args: Array[String]) = {
    Actor.remote.start("localhost", 2552)
    Actor.remote.register("hello-service", actorOf[HelloWorldActor])
  }
}

object ServerInitiatedRemoteActorClient extends Logging {
  def main(args: Array[String]) = {
    val actor = Actor.remote.actorFor("hello-service", "localhost", 2552)
    val result = actor !! "Hello"
    log.slf4j.info("Result from Remote Actor: {}", result)
  }
}

