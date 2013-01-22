package akka.channels.macros

import akka.channels._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
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
  def impl[T: c.WeakTypeTag, C <: ChannelList: c.WeakTypeTag, P <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = Channels[P, C]
    }): c.Expr[Channels[P, C]#Behaviorist[Nothing, T]] = {

    val tT = c.weakTypeOf[T]
    val tC = c.weakTypeOf[C]

    import c.universe._

    val undefined = missingChannels(c.universe)(tC, inputChannels(c.universe)(tT))
    if (undefined.nonEmpty) {
      c.error(c.enclosingPosition, s"no channel defined for types ${undefined mkString ", "}")
      reify(null)
    } else {
      checkUnique(c.universe)(tT, tC) foreach (c.error(c.enclosingPosition, _))
      val channels = toChannels(c.universe)(replyChannels(c.universe)(tC, tT))
      val (receive, wrapped) =
        if (tT <:< typeOf[ChannelList]) {
          appliedType(typeOf[Function2[_, _, _]].typeConstructor, List(
            appliedType(typeOf[WrappedMessage[_]].typeConstructor, List(tT)),
            appliedType(typeOf[ChannelRef[_]].typeConstructor, List(channels)),
            typeOf[Unit])) -> true
        } else {
          appliedType(typeOf[Function2[_, _, _]].typeConstructor, List(
            tT,
            appliedType(typeOf[ChannelRef[_]].typeConstructor, List(channels)),
            typeOf[Unit])) -> false
        }
      c.Expr(
        Block(List(
          If(
            {
              val cltt = c.Expr(Select(c.prefix.tree, "channelListTypeTag"))
              reify(cltt.splice == null).tree
            },
            Apply(
              Select(c.prefix.tree, "channelListTypeTag_$eq"),
              List(TypeApply(
                Select(Select(Select(Select(Select(Ident("scala"), "reflect"), "runtime"), nme.PACKAGE), "universe"), "typeTag"),
                List(TypeTree().setType(c.weakTypeOf[C]))))),
            c.literalUnit.tree)),
          Apply(
            Select(
              New(AppliedTypeTree(Select(c.prefix.tree, newTypeName("Behaviorist")), List(
                TypeTree().setType(receive),
                TypeTree().setType(tT)))),
              nme.CONSTRUCTOR),
            List(
              TypeApply(
                Select(Select(Select(Select(Select(Ident("scala"), "reflect"), "runtime"), nme.PACKAGE), "universe"), "typeTag"),
                List(TypeTree().setType(tT))),
              Literal(Constant(wrapped))))))
    }
  }

}