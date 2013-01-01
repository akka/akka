/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import language.experimental.macros
import akka.actor.Actor
import scala.reflect.macros.Context
import scala.reflect.runtime.universe.TypeTag
import scala.reflect.macros.Universe
import scala.runtime.AbstractPartialFunction
import akka.actor.Props
import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ classTag, ClassTag }

/**
 * Typed channels atop untyped actors.
 *
 * The idea is that the actor declares all its input types up front, including
 * what it expects the sender to handle wrt.replies, and then ChannelRef
 * carries this information for statically verifying that messages sent to an
 * actor have an actual chance of being processed.
 *
 * There are several implementation-imposed restrictions:
 *
 *  - not two channels with different input types may have the same erased
 *    type; this is currently not enforced at compile time (and leads to
 *    channels being “ignored” at runtime)
 *  - messages received by the actor are dispatched to channels based on the
 *    erased type, which may be less precise than the actual channel type; this
 *    can lead to ClassCastExceptions if sending through the untyped ActorRef
 */
class Channels[P <: ChannelList, C <: ChannelList: TypeTag] extends Actor {

  import Channels._

  /**
   * Create a child actor with properly typed ChannelRef, verifying that this
   * actor can handle everything which the child tries to send via its
   * `parent` ChannelRef.
   */
  def createChild[Pa <: ChannelList, Ch <: ChannelList](factory: Channels[Pa, Ch]): ChannelRef[Ch] = macro createChildImpl[C, Pa, Ch]

  /**
   * Properly typed ChannelRef for the context.parent.
   */
  def parentChannel: ChannelRef[P] = new ChannelRef(context.parent)

  /**
   * The properly typed self-channel is used implicitly when sending to other
   * typed channels for verifying that replies can be handled.
   */
  implicit def selfChannel = new ChannelRef[C](self)

  private var behavior = Map.empty[Class[_], Recv[Any, ChannelList]]

  /**
   * Declare an input channel of the given type; the returned object takes a partial function:
   *
   * {{{
   * channel[A] {
   *   case (a, s) =>
   *     // a is of type A and
   *     // s is a ChannelRef for the sender, capable of sending the declared reply type for A
   * }
   * }}}
   */
  def channel[T]: Channels[P, C]#Behaviorist[T, _ <: ChannelList] = macro channelImpl[T, C, P, ChannelList]

  protected def _channel[T, Ch <: ChannelList](cls: Class[_]) = new Behaviorist[T, Ch](cls)
  protected class Behaviorist[T, Ch <: ChannelList](cls: Class[_]) {
    def apply(recv: Recv[T, Ch]): Unit =
      behavior += cls -> recv.asInstanceOf[Recv[Any, ChannelList]]
  }

  /**
   * Sort so that subtypes always precede their supertypes, but without
   * obeying any order between unrelated subtypes (insert sort).
   */
  private def sortClasses(in: Iterable[Class[_]]): immutable.Seq[Class[_]] =
    (new ArrayBuffer[Class[_]](in.size) /: in) { (buf, cls) ⇒
      buf.indexWhere(_ isAssignableFrom cls) match {
        case -1 ⇒ buf append cls
        case x  ⇒ buf insert (x, cls)
      }
      buf
    }.to[immutable.IndexedSeq]

  final lazy val receive = new AbstractPartialFunction[Any, Unit] {

    val index = sortClasses(behavior.keys)

    override def applyOrElse[A, B >: Unit](x: A, default: A ⇒ B): B = {
      val msgClass = x.getClass
      index find (_ isAssignableFrom msgClass) match {
        case None      ⇒ default(x)
        case Some(cls) ⇒ behavior(cls).applyOrElse((x, new ChannelRef(sender)), (pair: (A, ChannelRef[C])) ⇒ default(pair._1))
      }
    }

    def isDefinedAt(x: Any): Boolean = {
      val msgClass = x.getClass
      index find (_ isAssignableFrom msgClass) match {
        case None      ⇒ false
        case Some(cls) ⇒ behavior(cls).isDefinedAt((x, new ChannelRef(sender)))
      }
    }
  }
}

object Channels {

  type Recv[T, Ch <: ChannelList] = PartialFunction[(T, ChannelRef[Ch]), Unit]

  /**
   * This macro transforms a channel[] call which returns “some” Behaviorist
   * into a _channel[] call with precise reply channel descriptors, so that the
   * partial function it is applied to can enjoy proper type checking.
   */
  def channelImpl[T: c.WeakTypeTag, C <: ChannelList: c.WeakTypeTag, P <: ChannelList: c.WeakTypeTag, Ch <: ChannelList](
    c: Context {
      type PrefixType = Channels[P, C]
    }): c.Expr[Channels[P, C]#Behaviorist[T, Ch]] = {

    import c.universe._
    val out = replyChannels(c.universe)(c.weakTypeOf[C], c.weakTypeOf[T])
    if (out.isEmpty) {
      c.error(c.enclosingPosition, s"no channel defined for type ${c.weakTypeOf[T]}")
      reify(null)
    } else {
      val channels = toChannels(c.universe)(out)
      c.Expr(Apply(
        TypeApply(
          Select(c.prefix.tree, "_channel"), List(
            TypeTree().setType(c.weakTypeOf[T]),
            TypeTree().setType(channels))),
        List(Select(
          TypeApply(
            Select(Select(Ident("scala"), "reflect"), "classTag"),
            List(TypeTree().setType(c.weakTypeOf[T]))),
          "runtimeClass"))))
    }
  }

  def createChildImpl[C <: ChannelList: c.WeakTypeTag, Pa <: ChannelList: c.WeakTypeTag, Ch <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = Channels[_, C]
    })(factory: c.Expr[Channels[Pa, Ch]]): c.Expr[ChannelRef[Ch]] = {

    import c.universe._
    if (weakTypeOf[Pa] =:= weakTypeOf[Nothing]) {
      c.abort(c.enclosingPosition, "Parent argument must not be Nothing")
    }
    if (weakTypeOf[Ch] =:= weakTypeOf[Nothing]) {
      c.abort(c.enclosingPosition, "channel list must not be Nothing")
    }
    val missing = missingChannels(c.universe)(weakTypeOf[C], inputChannels(c.universe)(weakTypeOf[Pa]))
    if (missing.isEmpty) {
      implicit val t = c.TypeTag[Ch](c.weakTypeOf[Ch])
      reify(new ChannelRef[Ch](c.prefix.splice.context.actorOf(Props(factory.splice))))
    } else {
      c.error(c.enclosingPosition, s"This actor cannot support a child requiring channels ${missing mkString ", "}")
      reify(???)
    }
  }

  /**
   * get all required channels from a Parent[_]
   */
  final def inputChannels(u: Universe)(list: u.Type): List[u.Type] = {
    import u._
    def rec(l: u.Type, acc: List[u.Type]): List[u.Type] = l match {
      case TypeRef(_, _, TypeRef(_, _, in :: _) :: tail :: Nil) ⇒ rec(tail, in :: acc)
      case _ ⇒ acc.reverse
    }
    rec(list, Nil)
  }

  /**
   * find all input channels matching the given message type and return a
   * list of their respective reply channels
   */
  final def replyChannels(u: Universe)(list: u.Type, msg: u.Type): List[u.Type] = {
    import u._
    def rec(l: Type, acc: List[Type]): List[Type] = {
      l match {
        case TypeRef(_, _, TypeRef(_, _, in :: out :: Nil) :: tail :: Nil) if msg <:< in ⇒
          rec(tail, out :: acc)
        case TypeRef(_, _, _ :: tail :: Nil) ⇒
          rec(tail, acc)
        case _ ⇒ acc.reverse
      }
    }
    rec(list, Nil)
  }

  /**
   * filter from the `required` list of types all which are subtypes of inputs of the ChannelList
   */
  final def missingChannels(u: Universe)(channels: u.Type, required: List[u.Type]): List[u.Type] = {
    import u._
    // making the top-level method recursive blows up the compiler (when compiling the macro itself)
    def rec(ch: Type, req: List[Type]): List[Type] = {
      ch match {
        case TypeRef(_, _, TypeRef(_, _, in :: _) :: (tail: Type) :: Nil) ⇒ rec(tail, req filterNot (_ <:< in))
        case _ ⇒ req
      }
    }
    rec(channels, required)
  }

  /**
   * convert a list of types List(<T1>, <T2>, ...) into a ChannelList
   * ( Channel[<T1>, Nothing] :=: Channel[<T2>, Nothing] :=: ... :=: TNil )
   */
  final def toChannels(u: Universe)(list: List[u.Type]): u.Type = {
    import u._
    def rec(l: List[Type], acc: Type): Type = l match {
      case head :: (tail: List[Type]) ⇒
        rec(tail,
          appliedType(weakTypeOf[:=:[_, _]].typeConstructor, List(
            appliedType(weakTypeOf[Channel[_, _]].typeConstructor, List(
              head,
              weakTypeOf[Nothing])),
            acc)))
      case _ ⇒ acc
    }
    rec(list.reverse, weakTypeOf[TNil])
  }

}