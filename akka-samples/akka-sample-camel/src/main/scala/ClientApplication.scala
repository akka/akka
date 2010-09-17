package sample.camel

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.TypedActor
import se.scalablesolutions.akka.camel.Message
import se.scalablesolutions.akka.remote.RemoteClient

/**
 * @author Martin Krasser
 */
object ClientApplication extends Application {

  val actor1 = actorOf[RemoteActor1].start
  val actor2 = RemoteClient.actorFor("remote2", "localhost", 7777)

  val typedActor1 = TypedActor.newRemoteInstance(
    classOf[RemoteTypedConsumer1],
    classOf[RemoteTypedConsumer1Impl], "localhost", 7777)

  val typedActor2 = RemoteClient.typedActorFor(
    classOf[RemoteTypedConsumer2], "remote3", "localhost", 7777)

  println(actor1 !! Message("actor1")) // activates and publishes actor remotely
  println(actor2 !! Message("actor2")) // actor already activated and published remotely

  println(typedActor1.foo("x1", "y1")) // activates and publishes typed actor methods remotely
  println(typedActor2.foo("x2", "y2")) // typed actor methods already activated and published remotely

}
