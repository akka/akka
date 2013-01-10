/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.actor.{ ActorRef, ActorSystem, ExtensionKey }

object IO {

  trait Extension extends akka.actor.Extension {
    def manager: ActorRef
  }

  def apply[T <: Extension](key: ExtensionKey[T])(implicit system: ActorSystem): ActorRef = key(system).manager

}