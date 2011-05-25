package sample.camel

import akka.actor.Actor._
import akka.camel.CamelServiceManager
import akka.actor.{TypedActor}

/**
 * @author Martin Krasser
 */
object ServerApplication extends App {
  import CamelServiceManager._

  startCamelService

  val ua = actorOf[RemoteActor2].start
  val ta = TypedActor.newInstance(
    classOf[RemoteTypedConsumer2],
    classOf[RemoteTypedConsumer2Impl], 2000)

  remote.start("localhost", 7777)
  remote.register("remote2", ua)
  remote.registerTypedActor("remote3", ta)
}
