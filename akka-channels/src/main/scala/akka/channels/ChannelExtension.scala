/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import akka.actor.ExtensionKey
import akka.actor.Extension
import akka.actor.ExtendedActorSystem
import scala.reflect.runtime.universe._
import akka.actor.Props
import scala.reflect.ClassTag
import scala.reflect.runtime.universe
import akka.actor.Actor

object ChannelExt extends ExtensionKey[ChannelExtension]

class ChannelExtension(system: ExtendedActorSystem) extends Extension {

  // kick-start the universe (needed due to thread safety issues in runtime mirror)
  private val t = typeTag[(Int, Int) :+: TNil]

  def actorOf[Ch <: ChannelList](factory: ⇒ Actor with Channels[TNil, Ch]): ChannelRef[Ch] =
    new ChannelRef[Ch](system.actorOf(Props(factory)))
}
