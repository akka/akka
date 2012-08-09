/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.collection.mutable.Queue
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import akka.pattern.ask
import scala.concurrent.Await
import akka.util.Timeout
import scala.collection.immutable.TreeSet
import scala.concurrent.util.Deadline
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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
 * implicit val recv = newInbox()
 * someActor ! someMsg // replies will go to `recv`
 *
 * val reply = receive(recv, 5 seconds)
 * val transformedReply = select(recv, 5 seconds) {
 *   case x: Int => 2 * x
 * }
 * }}}
 * 
 * The `receive` and `select` methods are synchronous, i.e. they block the
 * calling thread until an answer from the actor is received or the timeout
 * expires.
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

  private class Inbox(size: Int) extends Actor with ActorLogging {
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
        if (currentDeadline.isEmpty) {
          currentDeadline = Some(next, context.system.scheduler.scheduleOnce(next.timeLeft, self, Kick))
        } else if (currentDeadline.get._1 != next) {
          currentDeadline.get._2.cancel()
          currentDeadline = Some(next, context.system.scheduler.scheduleOnce(next.timeLeft, self, Kick))
        }
      }
    }
  }

  private object Extension extends ExtensionKey[Extension]
  private class Extension(system: ExtendedActorSystem) extends akka.actor.Extension {
    val boss = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props.empty, "dsl").asInstanceOf[RepointableActorRef]
    while (!boss.isStarted) Thread.sleep(10)

    val DSLInboxQueueSize = system.settings.config.getInt("akka.actor.dsl.inbox-size")

    val inboxNr = new AtomicInteger
    val inboxProps = Props(new Inbox(DSLInboxQueueSize))

    def newInbox(): ActorRef =
      boss.underlying.asInstanceOf[ActorCell]
        .attachChild(inboxProps, "inbox-" + inboxNr.incrementAndGet(), systemService = true)
  }

  /*
   * make sure that AskTimeout does not accidentally mess up message reception
   * by adding this extra time to the real timeout
   */
  private val extraTime = 1.minute

  private val pathPrefix = Seq("system", "dsl")
  private def mustBeInbox(inbox: ActorRef) {
    require(inbox.path.elements.take(2) == pathPrefix, "can only use select/receive with references obtained from newInbox()")
  }

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
  def newInbox()(implicit system: ActorSystem): ActorRef = Extension(system).newInbox()

  /**
   * Receive a single message using the actor reference supplied; this must be
   * an actor created using `newReceiver()` above. The supplied timeout is used
   * for cleanup purposes and its precision is subject to the resolution of the
   * system’s scheduler (usually 100ms, but configurable).
   */
  def receive(inbox: ActorRef, timeout: Duration): Any = {
    mustBeInbox(inbox)
    implicit val t = Timeout(timeout + extraTime)
    Await.result(inbox ? Get(Deadline.now + timeout), Duration.Inf)
  }

  /**
   * Receive a single message for which the given partial function is defined
   * and return the transformed result, using the actor reference supplied;
   * this must be an actor created using `newReceiver()` above. The supplied
   * timeout is used for cleanup purposes and its precision is subject to the
   * resolution of the system’s scheduler (usually 100ms, but configurable).
   */
  def select[T](inbox: ActorRef, timeout: Duration)(predicate: PartialFunction[Any, T]): T = {
    mustBeInbox(inbox)
    implicit val t = Timeout(timeout + extraTime)
    predicate(Await.result(inbox ? Select(Deadline.now + timeout, predicate), Duration.Inf))
  }

}