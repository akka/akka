/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.implicitConversions

import java.util.regex.Pattern
import akka.util.Helpers

/**
 * An ActorSelection is a logical view of a section of an ActorSystem's tree of Actors,
 * allowing for broadcasting of messages to that section.
 */
abstract class ActorSelection {
  this: ScalaActorSelection ⇒

  protected def target: ActorRef

  protected def path: Array[AnyRef]

  def tell(msg: Any): Unit = target ! toMessage(msg, path)

  def tell(msg: Any, sender: ActorRef): Unit = target.tell(toMessage(msg, path), sender)

  private def toMessage(msg: Any, path: Array[AnyRef]): Any = {
    var acc = msg
    var index = path.length - 1
    while (index >= 0) {
      acc = path(index) match {
        case ".."       ⇒ SelectParent(acc)
        case s: String  ⇒ SelectChildName(s, acc)
        case p: Pattern ⇒ SelectChildPattern(p, acc)
      }
      index -= 1
    }
    acc
  }
}

/**
 * An ActorSelection is a logical view of a section of an ActorSystem's tree of Actors,
 * allowing for broadcasting of messages to that section.
 */
object ActorSelection {
  //This cast is safe because the self-type of ActorSelection requires that it mixes in ScalaActorSelection
  implicit def toScala(sel: ActorSelection): ScalaActorSelection = sel.asInstanceOf[ScalaActorSelection]

  /**
   * Construct an ActorSelection from the given string representing a path
   * relative to the given target. This operation has to create all the
   * matching magic, so it is preferable to cache its result if the
   * intention is to send messages frequently.
   */
  def apply(anchor: ActorRef, path: String): ActorSelection = {
    val elems = path.split("/+").dropWhile(_.isEmpty)
    val compiled: Array[AnyRef] = elems map (x ⇒ if ((x.indexOf('?') != -1) || (x.indexOf('*') != -1)) Helpers.makePattern(x) else x)
    new ActorSelection with ScalaActorSelection {
      def target = anchor
      def path = compiled
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