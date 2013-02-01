/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels.macros

import akka.channels._
import scala.reflect.runtime.universe
import scala.reflect.macros.Context
import scala.reflect.api.Universe

object Channel {
  import Helpers._

  /**
   * This macro transforms a channel[] call which returns “some” Behaviorist
   * into a _channel[] call with precise reply channel descriptors, so that the
   * partial function it is applied to can enjoy proper type checking.
   *
   * T is the message type
   * C is the channel list of the enclosing Channels
   * P is the parent channel list
   */
  def impl[LUB, ReplyChannels <: ChannelList, MsgTChan <: ChannelList, MsgT: c.WeakTypeTag, MyChannels <: ChannelList: c.WeakTypeTag, ParentChannels <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = Channels[ParentChannels, MyChannels]
    }): c.Expr[(Nothing ⇒ Unit)] = {

    val tpeMsgT = c.weakTypeOf[MsgT]
    val tpeMyChannels = c.weakTypeOf[MyChannels]

    import c.universe._

    val undefined = missingChannels(c.universe)(tpeMyChannels, inputChannels(c.universe)(tpeMsgT))
    if (undefined.nonEmpty) {
      c.abort(c.enclosingPosition, s"no channel defined for types ${undefined mkString ", "}")
    } else {
      checkUnique(c.universe)(tpeMsgT, tpeMyChannels) foreach (c.error(c.enclosingPosition, _))
      // need to calculate the intersection of the reply channel sets for all input channels
      val intersection = inputChannels(c.universe)(tpeMsgT) map (replyChannels(c.universe)(tpeMyChannels, _).toSet) reduce (_ intersect _)
      val channels = toChannels(c.universe)(intersection.toList, weakTypeOf[UnknownDoNotWriteMeDown])
      implicit val ttMyChannels = c.TypeTag[MyChannels](tpeMyChannels)
      implicit val ttReplyChannels = c.TypeTag[ReplyChannels](channels)
      implicit val ttMsgT = c.TypeTag[MsgT](tpeMsgT)
      val prepTree = reify(if (c.prefix.splice.channelListTypeTag == null)
        c.prefix.splice.channelListTypeTag = universe.typeTag[MyChannels])
      if (tpeMsgT <:< typeOf[ChannelList]) {
        implicit val ttMsgTChan = c.TypeTag[MsgTChan](tpeMsgT) // this is MsgT reinterpreted as <: ChannelList
        implicit val ttLUB = inputChannels(c.universe)(tpeMsgT) match {
          case x :: Nil ⇒ c.TypeTag[LUB](typeOf[Any])
          case xs       ⇒ c.TypeTag[LUB](c.universe.lub(xs))
        }
        reify {
          prepTree.splice
          c.prefix.splice.behaviorist[(WrappedMessage[MsgTChan, LUB], ChannelRef[ReplyChannels]) ⇒ Unit, MsgT](
            bool(c, true).splice)(universe.typeTag[MsgT])
        }
      } else
        reify {
          prepTree.splice
          c.prefix.splice.behaviorist[(MsgT, ChannelRef[ReplyChannels]) ⇒ Unit, MsgT](
            bool(c, false).splice)(universe.typeTag[MsgT])
        }
    }
  }

}