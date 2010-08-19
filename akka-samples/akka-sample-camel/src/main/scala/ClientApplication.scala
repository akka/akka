package sample.camel

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{TypedActor, Actor, ActorRef}
import se.scalablesolutions.akka.camel.Message
import se.scalablesolutions.akka.remote.RemoteClient

/**
 * @author Martin Krasser
 */
object ClientApplication extends Application {

  //
  // TODO: completion of example
  //

  val actor1 = actorOf[RemoteActor1]
  val actor2 = RemoteClient.actorFor("remote2", "localhost", 7777)

  val actobj1 = TypedActor.newRemoteInstance(
    classOf[RemoteTypedConsumer1], classOf[RemoteTypedConsumer1Impl], "localhost", 7777)
  //val actobj2 = TODO: create reference to server-managed typed actor (RemoteTypedConsumer2Impl)

  actor1.start

  println(actor1 !! Message("actor1")) // activates and publishes actor remotely
  println(actor2 !! Message("actor2")) // actor already activated and published remotely

  println(actobj1.foo("x", "y"))       // activates and publishes typed actor methods remotely
  // ...
}
