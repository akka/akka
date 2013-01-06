/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import language.experimental.macros
import akka.actor.{ Actor, ActorRef }
import scala.reflect.macros.Context
import scala.reflect.runtime.{ universe ⇒ ru }
import scala.reflect.runtime.universe.TypeTag
import scala.reflect.api.Universe
import scala.runtime.AbstractPartialFunction
import akka.actor.Props
import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ classTag, ClassTag }
import scala.concurrent.{ ExecutionContext, Future }
import akka.util.Timeout
import akka.pattern.ask
import scala.util.control.NoStackTrace
import akka.AkkaException
import akka.actor.ExtendedActorSystem

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
trait Channels[P <: ChannelList, C <: ChannelList] extends Actor {

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

  /*
   * This map holds the current behavior for each erasure-tagged channel; the
   * basic receive impl will dispatch incoming messages according to the most
   * specific erased type in this map.
   */
  private var behavior = Map.empty[Class[_], FF]

  private trait FF
  private object FF {
    def apply(x: Any): FF = x match {
      case f: Function1[_, _]    ⇒ F1(f.asInstanceOf[Values.WrappedMessage[ChannelList] ⇒ Unit])
      case f: Function2[_, _, _] ⇒ F2(f.asInstanceOf[(Any, ChannelRef[ChannelList]) ⇒ Unit])
    }
  }
  private case class F1(f: Values.WrappedMessage[ChannelList] ⇒ Unit) extends FF
  private case class F2(f: (Any, ChannelRef[ChannelList]) ⇒ Unit) extends FF

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
  def channel[T]: Channels[P, C]#Behaviorist[Nothing, T] = macro channelImpl[T, C, P]

  new Behaviorist(null)

  protected class Behaviorist[-R, Ch](tt: ru.TypeTag[Ch]) {
    def apply(recv: R): Unit =
      behavior ++= (for (t ← inputChannels(ru)(tt.tpe)) yield tt.mirror.runtimeClass(t.widen) -> FF(recv))
  }

  /*
   * HORRIBLE HACK AHEAD
   * 
   * I’d like to keep this a trait, but traits cannot have constructor 
   * arguments, not even TypeTags.
   */
  protected var channelListTypeTag: TypeTag[C] = _

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

    override def applyOrElse[A, B >: Unit](x: A, default: A ⇒ B): B = x match {
      case CheckType(tt) ⇒
        narrowCheck(ru)(channelListTypeTag.tpe, tt.tpe) match {
          case Nil        ⇒ sender ! CheckTypeACK
          case err :: Nil ⇒ sender ! CheckTypeNAK(err)
          case list       ⇒ sender ! CheckTypeNAK(list mkString ("multiple errors:\n  - ", "  - ", ""))
        }
      case _ ⇒
        val msgClass = x.getClass
        index find (_ isAssignableFrom msgClass) match {
          case None ⇒ default(x)
          case Some(cls) ⇒
            behavior(cls) match {
              case F1(f) ⇒ f(new Values.WrappedMessage[ChannelList](x))
              case F2(f) ⇒ f(x, new ChannelRef(sender))
            }
        }
    }

    def isDefinedAt(x: Any): Boolean = x match {
      case c: CheckType[_] ⇒ true
      case _ ⇒
        val msgClass = x.getClass
        index find (_ isAssignableFrom msgClass) match {
          case None      ⇒ false
          case Some(cls) ⇒ true
        }
    }
  }
}

object Channels {

  type Recv[T, Ch <: ChannelList] = Function2[T, ChannelRef[Ch], Unit]

  case class CheckType[T](tt: TypeTag[T])
  case object CheckTypeACK
  case class CheckTypeNAK(errors: String)
  case class NarrowingException(errors: String) extends AkkaException(errors) with NoStackTrace

  /**
   * This macro transforms a channel[] call which returns “some” Behaviorist
   * into a _channel[] call with precise reply channel descriptors, so that the
   * partial function it is applied to can enjoy proper type checking.
   *
   * T is the message type
   * C is the channel list of the enclosing Channels
   * P is the parent channel list
   */
  def channelImpl[T: c.WeakTypeTag, C <: ChannelList: c.WeakTypeTag, P <: ChannelList: c.WeakTypeTag](
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
      val receive =
        if (tT <:< typeOf[ChannelList]) {
          appliedType(typeOf[Function1[_, _]].typeConstructor, List(
            appliedType(typeOf[Values.WrappedMessage[_]].typeConstructor, List(tT)),
            typeOf[Unit]))
        } else {
          val channels = toChannels(c.universe)(replyChannels(c.universe)(tC, tT))
          appliedType(typeOf[Function2[_, _, _]].typeConstructor, List(
            tT,
            appliedType(typeOf[ChannelRef[_]].typeConstructor, List(channels)),
            typeOf[Unit]))
        }
      c.Expr(
        Apply(
          Select(
            New(AppliedTypeTree(Select(c.prefix.tree, newTypeName("Behaviorist")), List(
              TypeTree().setType(receive),
              TypeTree().setType(tT)))),
            nme.CONSTRUCTOR),
          List(
            Block(List(
              If(reify(c.prefix.splice.channelListTypeTag == null).tree,
                Apply(
                  Select(c.prefix.tree, "channelListTypeTag_$eq"),
                  List(TypeApply(
                    Select(Select(Select(Select(Select(Ident("scala"), "reflect"), "runtime"), nme.PACKAGE), "universe"), "typeTag"),
                    List(TypeTree().setType(c.weakTypeOf[C]))))),
                c.literalUnit.tree)),
              TypeApply(
                Select(Select(Select(Select(Select(Ident("scala"), "reflect"), "runtime"), nme.PACKAGE), "universe"), "typeTag"),
                List(TypeTree().setType(tT)))))))
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
   * check that the original ChannelList is a subtype of the target ChannelList; return a list or error strings
   */
  def narrowCheck(u: Universe)(orig: u.Type, target: u.Type): List[String] = {
    var errors = List.empty[String]
    for (in ← inputChannels(u)(target)) {
      val replies = replyChannels(u)(orig, in)
      if (replies.isEmpty) errors ::= s"original ChannelRef does not support input type $in"
      else {
        val targetReplies = replyChannels(u)(target, in)
        val unsatisfied = replies filterNot (r ⇒ targetReplies exists (r <:< _))
        if (unsatisfied.nonEmpty) errors ::= s"reply types ${unsatisfied mkString ", "} not covered for channel $in"
        val leftovers = targetReplies filterNot (t ⇒ replies exists (_ <:< t))
        if (leftovers.nonEmpty) errors ::= s"desired reply types ${leftovers mkString ", "} are superfluous for channel $in"
      }
    }
    errors.reverse
  }

  /**
   * get all input channels from a ChannelList or return the given type
   */
  final def inputChannels(u: Universe)(list: u.Type): List[u.Type] = {
    import u._
    val imp = u.mkImporter(ru)
    val cl = imp.importType(ru.typeOf[ChannelList])
    val tnil = imp.importType(ru.typeOf[TNil])
    def rec(l: u.Type, acc: List[u.Type]): List[u.Type] = l match {
      case TypeRef(_, _, TypeRef(_, _, in :: _) :: tail :: Nil) ⇒ rec(tail, if (acc contains in) acc else in :: acc)
      case last ⇒ if (last =:= tnil) acc.reverse else (last :: acc).reverse
    }
    if (list <:< cl) rec(list, Nil)
    else List(list)
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
          rec(tail, if (acc contains out) acc else out :: acc)
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
        case TypeRef(_, _, TypeRef(_, _, in :: _) :: tail :: Nil) ⇒ rec(tail, req filterNot (_ <:< in))
        case last ⇒ req filterNot (_ <:< last)
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
        if (head =:= weakTypeOf[Nothing]) rec(tail, acc)
        else
          rec(tail,
            appliedType(weakTypeOf[:+:[_, _]].typeConstructor, List(
              appliedType(weakTypeOf[Tuple2[_, _]].typeConstructor, List(
                head,
                weakTypeOf[Nothing])),
              acc)))
      case _ ⇒ acc
    }
    rec(list.reverse, weakTypeOf[TNil])
  }

}