/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import java.util.regex.Pattern
import akka.util.Helpers

abstract class ActorSelection {
  this: ScalaActorSelection ⇒

  protected def target: ActorRef

  protected def path: Array[AnyRef]

  def tell(msg: Any) { target ! toMessage(msg, path) }

  def tell(msg: Any, sender: ActorRef) { target.tell(toMessage(msg, path), sender) }

  // this may want to be fast ...
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

object ActorSelection {
  implicit def toScala(sel: ActorSelection): ScalaActorSelection = sel.asInstanceOf[ScalaActorSelection]

  /**
   * Construct an ActorSelection from the given string representing a path
   * relative to the given target. This operation has to create all the
   * matching magic, so it is preferable to cache its result if the
   * intention is to send messages frequently.
   */
  def apply(anchor: ActorRef, path: String): ActorSelection = {
    val elems = path.split("/+").dropWhile(_.isEmpty)
    val compiled: Array[AnyRef] = elems map (x ⇒ if (x.contains("?") || x.contains("*")) Helpers.makePattern(x) else x)
    new ActorSelection with ScalaActorSelection {
      def target = anchor
      def path = compiled
    }
  }
}

trait ScalaActorSelection {
  this: ActorSelection ⇒

  def !(msg: Any)(implicit sender: ActorRef = null) = tell(msg, sender)
}