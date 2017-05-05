/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.testkit

import java.util.concurrent.{ ConcurrentLinkedQueue, ThreadLocalRandom }

import akka.actor.{ Address, RootActorPath }
import akka.typed.{ ActorRef, internal }

import scala.annotation.tailrec
import scala.collection.immutable

/**
 * Utility for receiving messages outside of an actor. No methods are provided
 * for synchronously awaiting a message, this is primarily useful for synchronous
 * tests of behaviors that send messages to other actors, where an Inbox’s ActorRef
 * can conveniently be used as a stub.
 */
class Inbox[T](name: String) {

  private val q = new ConcurrentLinkedQueue[T]

  val ref: ActorRef[T] = {
    val uid = ThreadLocalRandom.current().nextInt()
    val path = RootActorPath(Address("akka.typed.inbox", "anonymous")).child(name).withUid(uid)
    new internal.FunctionRef[T](path, (msg, self) ⇒ q.add(msg), (self) ⇒ ())
  }

  def receiveMsg(): T = q.poll() match {
    case null ⇒ throw new NoSuchElementException(s"polling on an empty inbox: $name")
    case x    ⇒ x
  }

  def receiveAll(): immutable.Seq[T] = {
    @tailrec def rec(acc: List[T]): List[T] = q.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }
    rec(Nil)
  }

  def hasMessages: Boolean = q.peek() != null
}

object Inbox {
  def apply[T](name: String): Inbox[T] = new Inbox(name)
}
