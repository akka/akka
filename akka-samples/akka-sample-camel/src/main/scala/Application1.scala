package sample.camel

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.camel.Message
import se.scalablesolutions.akka.remote.RemoteClient
import se.scalablesolutions.akka.actor.{ActiveObject, Actor, ActorRef}

/**
 * @author Martin Krasser
 */
object Application1 {

  //
  // TODO: completion of example
  //

  def main(args: Array[String]) {
    val actor1 = actorOf[RemoteActor1]
    val actor2 = RemoteClient.actorFor("remote2", "localhost", 7777)

    val actobj1 = ActiveObject.newRemoteInstance(classOf[RemoteActiveObject1], "localhost", 7777)
    //val actobj2 = TODO: create reference to server-managed active object (RemoteActiveObject2)

    actor1.start

    println(actor1 !! Message("actor1")) // activates and publishes actor remotely
    println(actor2 !! Message("actor2")) // actor already activated and published remotely

    println(actobj1.foo("x", "y"))       // activates and publishes active object methods remotely
    // ...
  }

}
