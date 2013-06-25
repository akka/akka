/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.implicitConversions
import scala.collection.immutable
import java.util.regex.Pattern
import akka.util.Helpers
import akka.routing.MurmurHash
import scala.annotation.tailrec

/**
 * An ActorSelection is a logical view of a section of an ActorSystem's tree of Actors,
 * allowing for broadcasting of messages to that section.
 */
@SerialVersionUID(1L)
abstract class ActorSelection extends Serializable {
  this: ScalaActorSelection ⇒

  import ActorSelection.PatternHolder

  protected[akka] val anchor: ActorRef

  protected val path: immutable.IndexedSeq[AnyRef]

  @deprecated("use the two-arg variant (typically getSelf() as second arg)", "2.2")
  def tell(msg: Any): Unit = tell(msg, Actor.noSender)

  def tell(msg: Any, sender: ActorRef): Unit = {
    @tailrec def toMessage(msg: Any, path: immutable.IndexedSeq[AnyRef], index: Int): Any =
      if (index < 0) msg
      else toMessage(
        path(index) match {
          case ".."             ⇒ SelectParent(msg)
          case s: String        ⇒ SelectChildName(s, msg)
          case p: PatternHolder ⇒ SelectChildPattern(p.pat, msg)
        }, path, index - 1)

    anchor.tell(toMessage(msg, path, path.length - 1), sender)
  }

  override def toString: String = {
    (new java.lang.StringBuilder).append("ActorSelection[").
      append(anchor.toString).
      append(path.mkString("/", "/", "")).
      append("]").toString
  }

  override def equals(obj: Any): Boolean = obj match {
    case s: ActorSelection ⇒ this.anchor == s.anchor && this.path == s.path
    case _                 ⇒ false
  }

  override lazy val hashCode: Int = {
    import MurmurHash._
    var h = startHash(anchor.##)
    h = extendHash(h, path.##, startMagicA, startMagicB)
    finalizeHash(h)
  }
}

/**
 * An ActorSelection is a logical view of a section of an ActorSystem's tree of Actors,
 * allowing for broadcasting of messages to that section.
 */
object ActorSelection {
  //This cast is safe because the self-type of ActorSelection requires that it mixes in ScalaActorSelection
  implicit def toScala(sel: ActorSelection): ScalaActorSelection = sel.asInstanceOf[ScalaActorSelection]

  @SerialVersionUID(1L) private case class PatternHolder(str: String) {
    val pat = Helpers.makePattern(str)
    override def toString = str
  }

  /**
   * Construct an ActorSelection from the given string representing a path
   * relative to the given target. This operation has to create all the
   * matching magic, so it is preferable to cache its result if the
   * intention is to send messages frequently.
   */
  def apply(anchorRef: ActorRef, path: String): ActorSelection = apply(anchorRef, path.split("/+"))

  /**
   * Construct an ActorSelection from the given string representing a path
   * relative to the given target. This operation has to create all the
   * matching magic, so it is preferable to cache its result if the
   * intention is to send messages frequently.
   */
  def apply(anchorRef: ActorRef, elements: Iterable[String]): ActorSelection = {
    val compiled: immutable.IndexedSeq[AnyRef] = elements.collect({
      case x if !x.isEmpty ⇒ if ((x.indexOf('?') != -1) || (x.indexOf('*') != -1)) PatternHolder(x) else x
    })(scala.collection.breakOut)
    new ActorSelection with ScalaActorSelection {
      override val anchor = anchorRef
      override val path = compiled
    }
  }
}

/**
 * Contains the Scala API (!-method) for ActorSelections) which provides automatic tracking of the sender,
 * as per the usual implicit ActorRef pattern.
 */
trait ScalaActorSelection {
  this: ActorSelection ⇒

  def !(msg: Any)(implicit sender: ActorRef = Actor.noSender) = tell(msg, sender)
}
