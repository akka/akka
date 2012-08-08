/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.collection.mutable.Queue
import scala.concurrent.util.Duration
import akka.pattern.ask
import scala.concurrent.Await
import akka.util.Timeout
import scala.collection.immutable.TreeSet
import scala.concurrent.util.Deadline
import java.util.concurrent.TimeoutException

/**
 * This object contains elements which make writing actors and related code
 * more concise, e.g. when trying out actors in the REPL.
 * 
 * For the communication of non-actor code with actors, you may use anonymous
 * actors tailored to this job:
 * 
 * {{{
 * import ActorDSL._
 * import concurrent.util.duration._
 * 
 * implicit val system: ActorSystem = ...
 * 
 * implicit val recv = newReceiver()
 * someActor ! someMsg // replies will go to `recv`
 * 
 * val reply = receiveMessage(recv, 5 seconds)
 * val transformedReply = selectMessage(recv, 5 seconds) {
 *   case x: Int => 2 * x
 * }
 * }}}
 */
object ActorDSL {

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

  private class Receiver extends Actor {
    var clients = Queue.empty[Query]
    val messages = Queue.empty[Any]
    var clientsByTimeout = TreeSet.empty[Query]

    def enqueue(q: Query) {
      val query = q withClient sender
      clients enqueue query
      clientsByTimeout += query
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
        if (messages.isEmpty) enqueue(g)
        else sender ! messages.dequeue()
      case s @ Select(_, predicate, _) ⇒
        if (messages.isEmpty) enqueue(s)
        else {
          currentSelect = s
          messages.dequeueFirst(messagePredicate) match {
            case Some(msg) ⇒ sender ! msg
            case None      ⇒ enqueue(s)
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
        if (clients.isEmpty) messages.enqueue(msg)
        else {
          currentMsg = msg
          clients.dequeueFirst(clientPredicate) match {
            case Some(q) ⇒ clientsByTimeout -= q; q.client ! msg
            case None    ⇒ messages.enqueue(msg)
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
        if (currentDeadline.isEmpty) {
          currentDeadline = Some(next, context.system.scheduler.scheduleOnce(next.timeLeft, self, Kick))
        } else if (currentDeadline.get._1 != next) {
          currentDeadline.get._2.cancel()
          currentDeadline = Some(next, context.system.scheduler.scheduleOnce(next.timeLeft, self, Kick))
        }
      }
    }
  }

  private val receiverProps = Props(new Receiver)

  /**
   * Create a new actor which will internally queue up messages it gets so that
   * they can be interrogated with the `receiveMessage()` and `selectMessage()`
   * methods below. It will be created as top-level actor in the ActorSystem
   * which is implicitly (or explicitly) supplied.
   * 
   * <b>IMPORTANT:</b>
   * 
   * Be sure to terminate this actor using `system.stop(ref)` where `system` is
   * the actor system with which the actor was created.
   */
  def newReceiver()(implicit system: ActorSystem): ActorRef = system.actorOf(receiverProps)

  /**
   * Receive a single message using the actor reference supplied; this must be
   * an actor created using `newReceiver()` above. The supplied timeout is used
   * for cleanup purposes and its precision is subject to the resolution of the
   * system’s scheduler (usually 100ms, but configurable).
   */
  def receiveMessage(receiver: ActorRef, timeout: Duration): Any = {
    implicit val t = Timeout(timeout * 2)
    Await.result(receiver ? Get(Deadline.now + timeout), Duration.Inf)
  }

  /**
   * Receive a single message for which the given partial function is defined
   * and return the transformed result, using the actor reference supplied; 
   * this must be an actor created using `newReceiver()` above. The supplied
   * timeout is used for cleanup purposes and its precision is subject to the
   * resolution of the system’s scheduler (usually 100ms, but configurable).
   */
  def selectMessage[T](receiver: ActorRef, timeout: Duration)(predicate: PartialFunction[Any, T]): T = {
    implicit val t = Timeout(timeout * 2)
    predicate(Await.result(receiver ? Select(Deadline.now + timeout, predicate), Duration.Inf))
  }

}