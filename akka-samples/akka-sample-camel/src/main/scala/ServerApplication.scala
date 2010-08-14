package sample.camel

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.camel.CamelService
import se.scalablesolutions.akka.remote.RemoteNode

/**
 * @author Martin Krasser
 */
object ServerApplication extends Application {

  //
  // TODO: completion of example
  //

  CamelService.start
  RemoteNode.start("localhost", 7777)
  RemoteNode.register("remote2", actorOf[RemoteActor2].start)
}
