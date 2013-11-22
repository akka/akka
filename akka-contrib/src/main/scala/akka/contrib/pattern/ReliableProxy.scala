/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.actor._
import akka.remote.RemoteScope
import scala.concurrent.duration._
import scala.util.Try

object ReliableProxy {
  def props(target: ActorRef, retryAfter: FiniteDuration, reconnectAfter: FiniteDuration,
            maxReconnects: Option[Int]): Props = {
    Props(classOf[ReliableProxy], target, retryAfter, reconnectAfter, maxReconnects)
  }

  def props(target: ActorRef, retryAfter: FiniteDuration, reconnectAfter: FiniteDuration): Props = {
    props(target, retryAfter, reconnectAfter, None)
  }

  class Receiver(target: ActorRef) extends Actor with DebugLogging {
    var lastSerial = 0

    context.watch(target)

    def receive = {
      case Message(msg, snd, serial) ⇒
        if (serial == lastSerial + 1) {
          target.tell(msg, snd)
          sender ! Ack(serial)
          lastSerial = serial
        } else if (compare(serial, lastSerial) <= 0) {
          sender ! Ack(serial)
        } else {
          logDebug(s"Received message from $snd with wrong serial: $msg")
        }
      case Terminated(`target`) ⇒ context stop self
    }
  }

  /**
   * Wrap-around aware comparison of integers: differences limited to 2**31-1
   * in magnitude will work correctly.
   */
  def compare(a: Int, b: Int): Int = {
    val c = a - b
    c match {
      case x if x < 0  ⇒ -1
      case x if x == 0 ⇒ 0
      case x if x > 0  ⇒ 1
    }
  }

  // Internal messages
  case class Message(msg: Any, sender: ActorRef, serial: Int)
  case class Ack(serial: Int)
  case object Tick
  case object ReconnectTick

  /**
   *  Wrap a message in NoRetry to have the proxy send the message directly to the target
   *  (not through the tunnel) without retrying.  This is useful if you need to send unreliable
   *  messages to the target but don't want to track changing target ActorRefs in your actor.
   */
  case class NoRetry(msg: Any)

  /**
   * TargetChanged is sent to transition subscribers when the target ActorRef has changed
   * (for example, the target system crashed and has been restarted).
   */
  case class TargetChanged(ref: ActorRef)

  /**
   * ProxyTerminated is sent to transition subscribers during postStop.  Any outstanding unsent
   * messages are contained the Unsent object.
   */
  case class ProxyTerminated(actor: ActorRef, outstanding: Unsent)
  case class Unsent(queue: Vector[Message])

  def receiver(target: ActorRef): Props = Props(classOf[Receiver], target)

  sealed trait State
  case object Idle extends State
  case object Active extends State
  case object Reconnect extends State

  // Java API
  val idle = Idle
  val active = Active
  val reconnect = Reconnect

  trait DebugLogging extends ActorLogging { this: Actor ⇒
    val debug = Try(context.system.settings.config.getBoolean("akka.actor.debug.reliable-proxy")) getOrElse false

    def logDebug(s: String) = if (debug) log.debug(s"$s [$self]")
  }
}

import ReliableProxy._

/**
 * A ReliableProxy is a means to wrap a remote actor reference in order to
 * obtain certain improved delivery guarantees:
 *
 *  - as long as the proxy is not terminated before it sends all of its queued
 *    messages then no messages will be lost
 *  - messages re-sent due to the first point will not be delivered out-of-order,
 *    message ordering is preserved
 *
 * These guarantees are valid for the communication between the two end-points
 * of the reliable “tunnel”, which usually spans an unreliable network. Delivery
 * from the remote end-point to the target actor is still subject to in-JVM
 * delivery semantics (i.e. not strictly guaranteed due to possible OutOfMemory
 * situations or other VM errors).
 *
 * You can create a reliable connection like this:
 * {{{
 * val proxy = context.actorOf(Props(new ReliableProxy(target)))
 * }}}
 * or in Java:
 * {{{
 * final ActorRef proxy = getContext().actorOf(new Props(new UntypedActorFactory() {
 *   public Actor create() {
 *     return new ReliableProxy(target);
 *   }
 * }));
 * }}}
 *
 * '''''Please note:''''' the tunnel is uni-directional, and original sender
 * information is retained, hence replies by the wrapped target reference will
 * go back in the normal “unreliable” way unless also secured by a ReliableProxy
 * from the remote end.
 *
 * ==Message Types==
 *
 * This actor is an akka.actor.FSM, hence it offers the service of
 * transition callbacks to those actors which subscribe using the
 * ``SubscribeTransitionCallBack`` and ``UnsubscribeTransitionCallBack``
 * messages; see akka.actor.FSM for more documentation. The proxy will
 * transition into ReliableProxy.Active state when ACKs are outstanding and
 * return to the ReliableProxy.Idle state when every message send so far
 * has been confirmed by the peer end-point.
 *
 * If a communication failure causes the tunnel to terminate via Remote Deathwatch
 * the proxy will transition into ReliableProxy.Reconnect state. In this state the
 * proxy will repeatedly send Identify messages to ActorSelection(target.path) in
 * order to obtain a new ActorRef for the target.  When an ActorIdentity for the
 * target is received a new tunnel will be created and the proxy will transition to
 * either Active or Idle, depending if there are any outstanding messages.  If the
 * target ActorRef has changed a TargetChanged message will be sent to the proxy's
 * transition subscribers.
 *
 * If the proxy is stopped and it still has outstanding messages a ProxyTerminated
 * message will be sent to the transition subscribers.  It contains an Unsent object
 * with the outstanding messages.
 *
 * If an Unsent message is sent to this actor the messages contained within it will
 * be relayed through the tunnel to the target.
 *
 * If a NoRetry message is sent the message it wraps will be forwarded to the target
 * directly (ie, not through the tunnel).  NoRetry messages have no delivery guarantees.
 *
 * Any other message type sent to this actor will be delivered via a remote-deployed
 * child actor to the designated target. All other message types declared in the
 * companion object are for internal use only and not to be sent from the outside.
 *
 * ==Failure Cases==
 *
 * All failures of either the local or the remote end-point are escalated to the
 * parent of this actor; there are no specific error cases which are predefined.
 *
 * ==Arguments==
 *
 * '''''target''''' is the akka.actor.ActorRef to which all messages will be
 * forwarded which are sent to this actor. It can be any type of actor reference,
 * but the “remote” tunnel endpoint will be deployed on the node where the target
 * ref points to.
 *
 * '''''retryAfter''''' is the ACK timeout after which all outstanding messages
 * will be resent. There is no limit on the queue size or the number of retries.
 *
 * '''''reconnectAfter''''' is the interval between reconnection attempts after the tunnel
 * is terminated.  The minimum recommended value for this is the sum of the properties
 * akka.remote.gate-invalid-addresses-for and akka.remote.quarantine-systems-for.
 *
 * '''''maxReconnects''''' is an optional maximum number of reconnection attempts for each
 * tunnel. Use None for unlimited.
 */
class ReliableProxy(target: ActorRef, retryAfter: FiniteDuration, reconnectAfter: FiniteDuration,
                    maxReconnects: Option[Int])
  extends Actor with LoggingFSM[State, Vector[Message]] with DebugLogging {

  var currentSerial: Int = _
  var currentTarget: ActorRef = _
  var attemptedReconnects: Int = _

  def createTunnel(target: ActorRef): ActorRef = {
    logDebug(s"Creating new tunnel for $target")
    val t = context.actorOf(receiver(target).withDeploy(Deploy(scope = RemoteScope(target.path.address))), "tunnel")

    context.watch(t)
    currentSerial = 0 // New tunnel expects first message to start with 1
    currentTarget = target
    attemptedReconnects = 0
    resetBackoff()
    t
  }

  if (target.path.address.host.isEmpty && self.path.address == target.path.address) {
    logDebug(s"Unnecessary to use ReliableProxy for local target: $target")
  }
  var tunnel = createTunnel(target)

  val resendTimer = "resend"
  val reconnectTimer = "reconnect"

  override def supervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }

  override def postStop() {
    logDebug(s"Stopping proxy and sending ${stateData.size} messages to parent in Unsent")
    gossip(ProxyTerminated(self, Unsent(stateData)))
    super.postStop()
  }

  startWith(Idle, Vector.empty)

  when(Idle) {
    case Event(Terminated(_), _) ⇒ terminated()
    case Event(Ack(_), _)        ⇒ stay()
    case Event(Unsent(msgs), _)  ⇒ goto(Active) using resend(msgs)
    case Event(NoRetry(msg), _)  ⇒ currentTarget forward msg; stay()
    case Event(msg, _)           ⇒ goto(Active) using Vector(send(msg, sender))
  }

  onTransition {
    case _ -> Active    ⇒ scheduleTick()
    case Active -> Idle ⇒ cancelTimer(resendTimer)
    case _ -> Reconnect ⇒ scheduleReconnectTick()
  }

  when(Active) {
    case Event(Terminated(_), _) ⇒
      terminated()
    case Event(Ack(serial), queue) ⇒
      val q = queue dropWhile (m ⇒ compare(m.serial, serial) <= 0)
      scheduleTick()
      if (q.isEmpty) goto(Idle) using Vector.empty
      else stay using q
    case Event(Tick, queue) ⇒
      logResend(queue.size)
      queue foreach { tunnel ! _ }
      scheduleTick()
      stay()
    case Event(Unsent(msgs), _) ⇒
      stay using resend(msgs)
    case Event(NoRetry(msg), _) ⇒
      currentTarget forward msg
      stay()
    case Event(msg, queue) ⇒
      stay using (queue :+ send(msg, sender))
  }

  when(Reconnect) {
    case Event(Terminated(_), _) ⇒
      stay()
    case Event(ActorIdentity(_, Some(actor)), queue) ⇒
      val curr = currentTarget
      cancelTimer(reconnectTimer)
      tunnel = createTunnel(actor)
      if (currentTarget != curr) gossip(TargetChanged(currentTarget))
      if (queue.isEmpty) goto(Idle) else goto(Active) using resend(queue)
    case Event(ActorIdentity(_, None), _) ⇒
      stay()
    case Event(ReconnectTick, _) ⇒
      if (maxReconnects exists (_ == attemptedReconnects)) {
        logDebug(s"Failed to reconnect after $attemptedReconnects")
        stop()
      } else {
        logDebug(s"${context.actorSelection(target.path)} ! ${Identify(target.path)}")
        context.actorSelection(target.path) ! Identify(target.path)
        scheduleReconnectTick()
        attemptedReconnects += 1
        stay()
      }
    case Event(NoRetry(msg), _) ⇒ // Drop msg
      stay()
    case Event(Unsent(msgs), queue) ⇒
      stay using queue ++ msgs
    case Event(msg, queue) ⇒
      stay using (queue :+ Message(msg, sender, nextSerial()))
  }

  def scheduleTick(): Unit = setTimer(resendTimer, Tick, retryAfter, repeat = false)

  def nextSerial(): Int = {
    currentSerial += 1
    currentSerial
  }

  def send(msg: Any, sender: ActorRef): Message = {
    val m = Message(msg, sender, nextSerial())
    tunnel ! m
    m
  }

  // Send q messages with new serial #s
  def resend(q: Vector[Message]): Vector[Message] = {
    logResend(q.size)
    q map { case Message(msg, sender, _) ⇒ send(msg, sender) }
  }

  def logResend(size: Int): Unit =
    logDebug(s"Resending $size messages through tunnel")

  def terminated(): State = {
    logDebug(s"Terminated: $target")
    goto(Reconnect)
  }

  def scheduleReconnectTick(): Unit = {
    val delay = nextBackoff()
    logDebug(s"Will attempt to reconnect to ${target.path} in $delay")
    setTimer(reconnectTimer, ReconnectTick, delay, repeat = false)
  }

  /**
   * Reset backoff interval.
   *
   * This and nextBackoff are meant to be implemented by subclasses.
   */
  def resetBackoff() {}

  /**
   * Returns the next retry interval duration.  By default each interval is the same, reconnectAfter.
   */
  def nextBackoff(): FiniteDuration = reconnectAfter
}