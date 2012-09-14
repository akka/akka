/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dsl

import scala.concurrent.Await
import akka.actor.ActorLogging
import scala.concurrent.util.Deadline
import scala.collection.immutable.TreeSet
import scala.concurrent.util.{ Duration, FiniteDuration }
import scala.concurrent.util.duration._
import akka.actor.Cancellable
import akka.actor.Actor
import scala.collection.mutable.Queue
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.util.Timeout
import akka.actor.Status
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import akka.pattern.ask
import akka.actor.ActorDSL
import akka.actor.Props

trait Inbox { this: ActorDSL.type ⇒

  protected trait InboxExtension { this: Extension ⇒
    val DSLInboxQueueSize = config.getInt("inbox-size")

    val inboxNr = new AtomicInteger
    val inboxProps = Props(new InboxActor(DSLInboxQueueSize))

    def newReceiver: ActorRef = mkChild(inboxProps, "inbox-" + inboxNr.incrementAndGet)
  }

  private sealed trait Query {
    def deadline: Deadline
    def withClient(c: ActorRef): Query
    def client: ActorRef
  }
  private case class Get(deadline: Deadline, client: ActorRef = null) extends Query {
    def withClient(c: ActorRef) = copy(client = c)
  }
  private case class Select(deadline: Deadline, predicate: PartialFunction[Any, Any], client: ActorRef = null) extends Query {
    def withClient(c: ActorRef) = copy(client = c)
  }
  private case object Kick
  private implicit val deadlineOrder: Ordering[Query] = new Ordering[Query] {
    def compare(left: Query, right: Query): Int = left.deadline.time compare right.deadline.time
  }

  private class InboxActor(size: Int) extends Actor with ActorLogging {
    var clients = Queue.empty[Query]
    val messages = Queue.empty[Any]
    var clientsByTimeout = TreeSet.empty[Query]
    var printedWarning = false

    def enqueueQuery(q: Query) {
      val query = q withClient sender
      clients enqueue query
      clientsByTimeout += query
    }

    def enqueueMessage(msg: Any) {
      if (messages.size < size) messages enqueue msg
      else {
        if (!printedWarning) {
          log.warning("dropping message: either your program is buggy or you might want to increase akka.actor.dsl.inbox-size, current value is " + size)
          printedWarning = true
        }
      }
    }

    var currentMsg: Any = _
    val clientPredicate: (Query) ⇒ Boolean = {
      case _: Get          ⇒ true
      case Select(_, p, _) ⇒ p isDefinedAt currentMsg
      case _               ⇒ false
    }

    var currentSelect: Select = _
    val messagePredicate: (Any ⇒ Boolean) = (msg) ⇒ currentSelect.predicate.isDefinedAt(msg)

    var currentDeadline: Option[(Deadline, Cancellable)] = None

    def receive = ({
      case g: Get ⇒
        if (messages.isEmpty) enqueueQuery(g)
        else sender ! messages.dequeue()
      case s @ Select(_, predicate, _) ⇒
        if (messages.isEmpty) enqueueQuery(s)
        else {
          currentSelect = s
          messages.dequeueFirst(messagePredicate) match {
            case Some(msg) ⇒ sender ! msg
            case None      ⇒ enqueueQuery(s)
          }
          currentSelect = null
        }
      case Kick ⇒
        val now = Deadline.now
        val pred = (q: Query) ⇒ q.deadline.time < now.time
        val overdue = clientsByTimeout.iterator.takeWhile(pred)
        while (overdue.hasNext) {
          val toKick = overdue.next()
          toKick.client ! Status.Failure(new TimeoutException("deadline passed"))
        }
        // TODO: this wants to lose the `Queue.empty ++=` part when SI-6208 is fixed
        clients = Queue.empty ++= clients.filterNot(pred)
        clientsByTimeout = clientsByTimeout.from(Get(now))
      case msg ⇒
        if (clients.isEmpty) enqueueMessage(msg)
        else {
          currentMsg = msg
          clients.dequeueFirst(clientPredicate) match {
            case Some(q) ⇒ clientsByTimeout -= q; q.client ! msg
            case None    ⇒ enqueueMessage(msg)
          }
          currentMsg = null
        }
    }: Receive) andThen { _ ⇒
      if (clients.isEmpty) {
        if (currentDeadline.isDefined) {
          currentDeadline.get._2.cancel()
          currentDeadline = None
        }
      } else {
        val next = clientsByTimeout.head.deadline
        import context.dispatcher
        if (currentDeadline.isEmpty) {
          currentDeadline = Some((next, context.system.scheduler.scheduleOnce(next.timeLeft, self, Kick)))
        } else if (currentDeadline.get._1 != next) {
          currentDeadline.get._2.cancel()
          currentDeadline = Some((next, context.system.scheduler.scheduleOnce(next.timeLeft, self, Kick)))
        }
      }
    }
  }

  /*
   * make sure that AskTimeout does not accidentally mess up message reception
   * by adding this extra time to the real timeout
   */
  private val extraTime = 1.minute

  /**
   * Create a new actor which will internally queue up messages it gets so that
   * they can be interrogated with the [[akka.actor.dsl.Inbox!.Inbox!.receive]]
   * and [[akka.actor.dsl.Inbox!.Inbox!.select]] methods. It will be created as
   * a system actor in the ActorSystem which is implicitly (or explicitly)
   * supplied.
   */
  def inbox()(implicit system: ActorSystem): Inbox = new Inbox(system)

  class Inbox(system: ActorSystem) {

    val receiver: ActorRef = Extension(system).newReceiver
    private val defaultTimeout: FiniteDuration = Extension(system).DSLDefaultTimeout

    /**
     * Receive a single message from the internal `receiver` actor. The supplied
     * timeout is used for cleanup purposes and its precision is subject to the
     * resolution of the system’s scheduler (usually 100ms, but configurable).
     *
     * <b>Warning:</b> This method blocks the current thread until a message is
     * received, thus it can introduce dead-locks (directly as well as
     * indirectly by causing starvation of the thread pool). <b>Do not use
     * this method within an actor!</b>
     */
    def receive(timeout: FiniteDuration = defaultTimeout): Any = {
      implicit val t = Timeout((timeout + extraTime).asInstanceOf[FiniteDuration])
      Await.result(receiver ? Get(Deadline.now + timeout), Duration.Inf)
    }

    /**
     * Receive a single message for which the given partial function is defined
     * and return the transformed result, using the internal `receiver` actor.
     * The supplied timeout is used for cleanup purposes and its precision is
     * subject to the resolution of the system’s scheduler (usually 100ms, but
     * configurable).
     *
     * <b>Warning:</b> This method blocks the current thread until a message is
     * received, thus it can introduce dead-locks (directly as well as
     * indirectly by causing starvation of the thread pool). <b>Do not use
     * this method within an actor!</b>
     */
    def select[T](timeout: FiniteDuration = defaultTimeout)(predicate: PartialFunction[Any, T]): T = {
      implicit val t = Timeout((timeout + extraTime).asInstanceOf[FiniteDuration])
      predicate(Await.result(receiver ? Select(Deadline.now + timeout, predicate), Duration.Inf))
    }

    /**
     * Overridden finalizer which will try to stop the actor once this Inbox
     * is no longer referenced.
     */
    override def finalize() {
      system.stop(receiver)
    }
  }

  implicit def senderFromInbox(implicit inbox: Inbox): ActorRef = inbox.receiver
}
