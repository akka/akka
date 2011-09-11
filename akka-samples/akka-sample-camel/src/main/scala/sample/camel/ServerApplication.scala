package sample.camel

import akka.actor.Actor._
import akka.camel.CamelServiceManager
import akka.actor.{ TypedActor, Props }

/**
 * @author Martin Krasser
 */
object ServerApplication extends App {
  import CamelServiceManager._

  /* TODO: fix remote example

  startCamelService

  val ua = actorOf[RemoteActor2]
  val ta = TypedActor.typedActorOf(
    classOf[RemoteTypedConsumer2],
    classOf[RemoteTypedConsumer2Impl], Props())

  remote.start("localhost", 7777)
  remote.register("remote2", ua)
  remote.registerTypedActor("remote3", ta)

  */
}
