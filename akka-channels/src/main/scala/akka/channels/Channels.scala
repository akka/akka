/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import language.experimental.{ macros ⇒ makkros }
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
import akka.actor.ActorInitializationException

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
trait Channels[P <: ChannelList, C <: ChannelList] { this: Actor ⇒

  import macros.Helpers._

  /**
   * Create a child actor with properly typed ChannelRef, verifying that this
   * actor can handle everything which the child tries to send via its
   * `parent` ChannelRef.
   */
  def createChild[Pa <: ChannelList, Ch <: ChannelList](factory: Actor with Channels[Pa, Ch]): ChannelRef[Ch] = macro macros.CreateChild.impl[C, Pa, Ch]

  /**
   * Properly typed ChannelRef for the context.parent.
   */
  final def parentChannel: ChannelRef[P] = new ChannelRef(context.parent)

  /**
   * The properly typed self-channel is used implicitly when sending to other
   * typed channels for verifying that replies can be handled.
   */
  implicit final def selfChannel = new ChannelRef[C](self)

  /*
   * This map holds the current behavior for each erasure-tagged channel; the
   * basic receive impl will dispatch incoming messages according to the most
   * specific erased type in this map.
   */
  private var behavior = Map.empty[Class[_], FF]

  /**
   * Functions for storage in the behavior, to get around erasure
   */
  private trait FF
  private case class F1(f: (WrappedMessage[ChannelList, Any], ChannelRef[ChannelList]) ⇒ Unit) extends FF
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
  def channel[T]: (Nothing ⇒ Unit) = macro macros.Channel.impl[Any, ChannelList, ChannelList, T, C, P]

  def behaviorist[R, Ch: ru.TypeTag](wrapped: Boolean): (R ⇒ Unit) = new Behaviorist[R, Ch](wrapped)
  private class Behaviorist[-R, Ch: ru.TypeTag](wrapped: Boolean) extends (R ⇒ Unit) {
    private def ff(recv: R): FF =
      if (wrapped)
        F1(recv.asInstanceOf[(WrappedMessage[ChannelList, Any], ChannelRef[ChannelList]) ⇒ Unit])
      else
        F2(recv.asInstanceOf[(Any, ChannelRef[ChannelList]) ⇒ Unit])
    def apply(recv: R): Unit = {
      val tt = implicitly[ru.TypeTag[Ch]]
      behavior ++= (for (t ← inputChannels(ru)(tt.tpe)) yield tt.mirror.runtimeClass(t.widen) -> ff(recv))
    }
  }

  /*
   * HORRIBLE HACK AHEAD
   * 
   * I’d like to keep this a trait, but traits cannot have constructor 
   * arguments, not even TypeTags.
   */
  var channelListTypeTag: TypeTag[C] = _

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

    if (channelListTypeTag != null) verifyCompleteness()

    private def verifyCompleteness() {
      val channels = inputChannels(ru)(channelListTypeTag.tpe)
      val classes = channels groupBy (e ⇒ channelListTypeTag.mirror.runtimeClass(e.widen))
      val missing = classes.keySet -- behavior.keySet
      if (missing.nonEmpty) {
        val m = missing.map(classes).flatten
        throw ActorInitializationException(s"missing declarations for channels ${m mkString ", "}")
      }
    }

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
              case F1(f) ⇒ f(new WrappedMessage[ChannelList, Any](x), new ChannelRef(sender))
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

}