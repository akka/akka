package sample.camel

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.remote.RemoteNode
import se.scalablesolutions.akka.camel.CamelServiceManager

/**
 * @author Martin Krasser
 */
object ServerApplication extends Application {
  import CamelServiceManager._

  //
  // TODO: completion of example
  //

  startCamelService
  RemoteNode.start("localhost", 7777)
  RemoteNode.register("remote2", actorOf[RemoteActor2].start)
}
