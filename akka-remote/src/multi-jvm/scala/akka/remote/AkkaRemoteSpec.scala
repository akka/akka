/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.testkit._
import akka.actor.ActorSystemImpl

abstract class AkkaRemoteSpec extends AkkaSpec with MultiJvmSync {

  /**
   * Helper function for accessing the underlying remoting.
   */
  def remote: Remote = {
    system.asInstanceOf[ActorSystemImpl].provider match {
      case r: RemoteActorRefProvider ⇒ r.remote
      case _                         ⇒ throw new Exception("Remoting is not enabled")
    }
  }

}
