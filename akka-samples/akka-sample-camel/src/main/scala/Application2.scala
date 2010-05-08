package sample.camel

import se.scalablesolutions.akka.camel.service.CamelService
import se.scalablesolutions.akka.remote.RemoteNode
import se.scalablesolutions.akka.actor.Actor._

/**
 * @author Martin Krasser
 */
object Application2 {

  //
  // TODO: completion of example
  //

  def main(args: Array[String]) {
    val camelService = CamelService.newInstance
    camelService.load
    RemoteNode.start("localhost", 7777)
    RemoteNode.register("remote2", actorOf[RemoteActor2].start)
  }
}