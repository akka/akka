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
  def impl[ReplyChannels <: ChannelList, MsgTChan <: ChannelList, MsgT: c.WeakTypeTag, MyChannels <: ChannelList: c.WeakTypeTag, ParentChannels <: ChannelList: c.WeakTypeTag](
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
      val channels = toChannels(c.universe)(replyChannels(c.universe)(tpeMyChannels, tpeMsgT))
      val wrapped = tpeMsgT <:< typeOf[ChannelList]
      implicit val ttMyChannels = c.TypeTag[MyChannels](tpeMyChannels)
      implicit val ttReplyChannels = c.TypeTag[ReplyChannels](channels)
      implicit val ttMsgT = c.TypeTag[MsgT](tpeMsgT)
      implicit val ttMsgTChan = c.TypeTag[MsgTChan](tpeMsgT) // this is MsgT reinterpreted as <: ChannelList
      val prepTree = reify(if (c.prefix.splice.channelListTypeTag == null)
        c.prefix.splice.channelListTypeTag = universe.typeTag[MyChannels])
      if (wrapped)
        reify {
          prepTree.splice
          c.prefix.splice.behaviorist[(WrappedMessage[MsgTChan], ChannelRef[ReplyChannels]) ⇒ Unit, MsgT](
            bool(c, wrapped).splice)(universe.typeTag[MsgT])
        }
      else
        reify {
          prepTree.splice
          c.prefix.splice.behaviorist[(MsgT, ChannelRef[ReplyChannels]) ⇒ Unit, MsgT](
            bool(c, wrapped).splice)(universe.typeTag[MsgT])
        }
    }
  }

}