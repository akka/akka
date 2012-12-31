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

class Channels[P <: Parent[_], C <: ChannelList: TypeTag] extends Actor {

  import Channels._

  def createChild[Pa <: Parent[_], Ch <: ChannelList](factory: Channels[Pa, Ch]): ChannelRef[Ch] = macro createChildImpl[C, Pa, Ch]

  implicit val selfChannel = new ChannelRef[C](self)

  /*
   * Warning Ugly Hack Ahead
   * 
   * The current problem is that the partial function literals shall be 
   * checked against the right types, but that leads to unfortunate 
   * optimizations in the pattern matcher (it leaves out instanceof checks
   * based on the static types). Hence the try-catch in receive’s 
   * applyOrElse implementation. What I’d really like is a way to re-create
   * the PF literals after type-checking but with a different expected type.
   */
  private var behavior = List.empty[Recv[Any, ChannelList]]

  def channel[T]: Channels[P, C]#Behaviorist[T, _ <: ChannelList] = macro channelImpl[T, C, P, ChannelList]

  protected def _channel[T, Ch <: ChannelList] = new Behaviorist[T, Ch]
  protected class Behaviorist[T, Ch <: ChannelList] {
    def apply(recv: Recv[T, Ch]): Unit = behavior ::= recv.asInstanceOf[Recv[Any, ChannelList]]
  }

  final lazy val receive = new AbstractPartialFunction[Any, Unit] {

    val behaviors = behavior.reverse

    override def applyOrElse[A, B >: Unit](x: A, default: A ⇒ B): B = {
      val envelope = (x, new ChannelRef(sender))
      def rec(list: List[Recv[Any, ChannelList]]): B = list match {
        case head :: tail ⇒
          try head.applyOrElse(envelope, (dummy: (A, ChannelRef[_])) ⇒ rec(tail))
          catch {
            // see comment above for why this ugliness
            case _: ClassCastException ⇒ rec(tail)
          }
        case _ ⇒ default(x)
      }
      rec(behaviors)
    }

    def isDefinedAt(x: Any): Boolean = {
      val envelope = (x, null) // hmm ...
      behaviors.exists(_.isDefinedAt(envelope))
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
  def channelImpl[T: c.WeakTypeTag, C <: ChannelList: c.WeakTypeTag, P <: Parent[_]: c.WeakTypeTag, Ch <: ChannelList](
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
      c.Expr(TypeApply(
        Select(c.prefix.tree, newTermName("_channel")), List(
          TypeTree().setType(c.weakTypeOf[T]),
          TypeTree().setType(channels))))
    }
  }

  def createChildImpl[C <: ChannelList: c.WeakTypeTag, Pa <: Parent[_]: c.WeakTypeTag, Ch <: ChannelList: c.WeakTypeTag](
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
    val missing = missingChannels(c.universe)(weakTypeOf[C], parentChannels(c.universe)(weakTypeOf[Pa]))
    if (missing.isEmpty) {
      implicit val t = c.TypeTag[Ch](c.weakTypeOf[Ch])
      reify(new ChannelRef[Ch](c.prefix.splice.context.actorOf(Props(factory.splice))))
    } else {
      c.error(c.enclosingPosition, s"This actor cannot support a child requiring channels ${missing mkString ", "}")
      reify(null)
    }
  }

  /**
   * get all required channels from a Parent[_]
   */
  final def parentChannels(u: Universe)(list: u.Type): List[u.Type] = {
    import u._
    def rec(l: u.Type, acc: List[u.Type]): List[u.Type] = l match {
      case TypeRef(_, _, ch :: tail :: Nil) ⇒ rec(tail, ch :: acc)
      case _                                ⇒ acc.reverse
    }
    list match {
      case TypeRef(_, _, ch :: Nil) ⇒ rec(ch, Nil)
    }
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