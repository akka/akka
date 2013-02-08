/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels.macros

import akka.channels._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe
import akka.actor.Props
import akka.actor.Actor

object CreateChild {
  import Helpers._

  def impl[MyChannels <: ChannelList: c.WeakTypeTag, ParentChannels <: ChannelList: c.WeakTypeTag, ChildChannels <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = Actor with Channels[_, MyChannels]
    })(factory: c.Expr[Actor with Channels[ParentChannels, ChildChannels]]): c.Expr[ChannelRef[ChildChannels]] = {

    import c.universe._

    verify(c)(weakTypeOf[ParentChannels], weakTypeOf[ChildChannels], weakTypeOf[MyChannels])

    implicit val t = c.TypeTag[ChildChannels](c.weakTypeOf[ChildChannels])
    reify(new ChannelRef[ChildChannels](c.prefix.splice.context.actorOf(Props(factory.splice))))
  }

  def implName[MyChannels <: ChannelList: c.WeakTypeTag, ParentChannels <: ChannelList: c.WeakTypeTag, ChildChannels <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = Actor with Channels[_, MyChannels]
    })(factory: c.Expr[Actor with Channels[ParentChannels, ChildChannels]], name: c.Expr[String]): c.Expr[ChannelRef[ChildChannels]] = {

    import c.universe._

    verify(c)(weakTypeOf[ParentChannels], weakTypeOf[ChildChannels], weakTypeOf[MyChannels])

    implicit val t = c.TypeTag[ChildChannels](c.weakTypeOf[ChildChannels])
    reify(new ChannelRef[ChildChannels](c.prefix.splice.context.actorOf(Props(factory.splice), name.splice)))
  }

  def verify(c: Context)(parent: c.Type, child: c.Type, mine: c.Type): Unit = {
    import c.universe._

    val nothing = weakTypeOf[Nothing]
    if (parent =:= nothing) abort(c, "Parent argument must not be Nothing")
    if (child =:= nothing) abort(c, "channel list must not be Nothing")

    val missing = missingChannels(c.universe)(mine, inputChannels(c.universe)(parent))
    if (missing.nonEmpty)
      abort(c, s"This actor cannot support a child requiring channels ${missing mkString ", "}")
  }

}