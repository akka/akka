package sample.camel

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.remote.RemoteNode
import se.scalablesolutions.akka.camel.CamelServiceManager
import se.scalablesolutions.akka.actor.TypedActor

/**
 * @author Martin Krasser
 */
object ServerApplication extends Application {
  import CamelServiceManager._

  startCamelService

  val ua = actorOf[RemoteActor2].start
  val ta = TypedActor.newInstance(
    classOf[RemoteTypedConsumer2],
    classOf[RemoteTypedConsumer2Impl], 2000)

  RemoteNode.start("localhost", 7777)
  RemoteNode.register("remote2", ua)
  RemoteNode.registerTypedActor("remote3", ta)
}
