/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.TreeSet
import scala.collection.mutable.Queue
import scala.concurrent.Await
import scala.concurrent.duration.{ Deadline, Duration, DurationInt, FiniteDuration }
import akka.{ actor ⇒ a }
import akka.pattern.ask
import akka.util.Helpers.ConfigOps
import akka.util.Timeout
import scala.concurrent.Future
import akka.actor.MinimalActorRef
import java.util.concurrent.ConcurrentLinkedQueue
import akka.actor.ActorPath
import akka.actor.RootActorPath
import akka.actor.Address
import scala.reflect.ClassTag
import scala.collection.immutable
import scala.annotation.tailrec
import akka.actor.ActorRefProvider
import scala.concurrent.ExecutionContext

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
