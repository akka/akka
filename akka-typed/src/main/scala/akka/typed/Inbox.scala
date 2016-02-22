/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import java.util.concurrent.ConcurrentLinkedQueue
import akka.actor.ActorPath
import akka.actor.RootActorPath
import akka.actor.Address
import scala.collection.immutable
import scala.annotation.tailrec
import akka.actor.ActorRefProvider

object Inbox {

  def sync[T](name: String): SyncInbox[T] = new SyncInbox(name)

  class SyncInbox[T](name: String) {
    private val q = new ConcurrentLinkedQueue[T]
    private val r = new akka.actor.MinimalActorRef {
      override def provider: ActorRefProvider = ???
      override val path: ActorPath = RootActorPath(Address("akka", "SyncInbox")) / name
      override def !(msg: Any)(implicit sender: akka.actor.ActorRef) = q.offer(msg.asInstanceOf[T])
    }

    val ref: ActorRef[T] = ActorRef(r)
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
}
