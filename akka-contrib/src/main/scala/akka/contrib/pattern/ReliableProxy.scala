/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.actor._
import akka.remote.RemoteScope
import scala.concurrent.duration._
import scala.util.Try

object ReliableProxy {
  /**
   * Scala API Props taking ActorRef.  Arguments are detailed in the [[akka.contrib.pattern.ReliableProxy!]]
   * constructor.
   */
  def props(targetRef: ActorRef, retryAfter: FiniteDuration, reconnectAfter: Option[FiniteDuration],
            maxReconnects: Option[Int]): Props = {
    Props(classOf[ReliableProxy], Left(targetRef), retryAfter, reconnectAfter, maxReconnects)
  }

  /**
   * Scala API Props taking ActorRef.  Arguments are detailed in the [[akka.contrib.pattern.ReliableProxy]]
   * constructor.
   */
  def props(targetPath: ActorPath, retryAfter: FiniteDuration, reconnectAfter: Option[FiniteDuration],
            maxReconnects: Option[Int]): Props = {
    Props(classOf[ReliableProxy], Right(targetPath), retryAfter, reconnectAfter, maxReconnects)
  }

  /**
   * Java API Props taking ActorRef.  Arguments are detailed in the [[akka.contrib.pattern.ReliableProxy]]
   * constructor.
   */
  def props(targetRef: ActorRef, retryAfter: FiniteDuration, reconnectAfter: FiniteDuration,
            maxReconnects: Int): Props = {
    props(targetRef, retryAfter, Some(reconnectAfter), if (maxReconnects > 0) Some(maxReconnects) else None)
  }

  /**
   * Java API Props taking ActorRef.  Arguments are detailed in the [[akka.contrib.pattern.ReliableProxy]]
   * constructor.
   */
  def props(targetPath: ActorPath, retryAfter: FiniteDuration, reconnectAfter: FiniteDuration,
            maxReconnects: Int): Props = {
    props(targetPath, retryAfter, Some(reconnectAfter), if (maxReconnects > 0) Some(maxReconnects) else None)
  }

  /**
   * Props taking ActorRef with no limit on reconnections.  Arguments are detailed in
   * the [[akka.contrib.pattern.ReliableProxy]] constructor.
   */
  def props(targetRef: ActorRef, retryAfter: FiniteDuration, reconnectAfter: FiniteDuration): Props = {
    props(targetRef, retryAfter, Some(reconnectAfter), None)
  }

  /**
   * Props taking ActorRef with no reconnections.  Arguments are detailed in
   * the [[akka.contrib.pattern.ReliableProxy]] constructor.
   */
  def props(targetRef: ActorRef, retryAfter: FiniteDuration): Props = {
    props(targetRef, retryAfter, None, None)
  }

  /**
   * Props taking ActorPath with no reconnections.  Arguments are detailed in
   * the [[akka.contrib.pattern.ReliableProxy]] constructor.
   */
  def props(targetPath: ActorPath, retryAfter: FiniteDuration): Props = {
    props(targetPath, retryAfter, None, None)
  }

  class Receiver(target: ActorRef, initialSerial: Int) extends Actor with DebugLogging {
    var lastSerial = initialSerial

    context.watch(target)

    def receive = {
      case Message(msg, snd, serial) ⇒
        if (serial == lastSerial + 1) {
          target.tell(msg, snd)
          sender() ! Ack(serial)
          lastSerial = serial
        } else if (compare(serial, lastSerial) <= 0) {
          sender() ! Ack(serial)
        } else {
          logDebug("Received message from {} with wrong serial: {}", snd, msg)
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

  def receiver(target: ActorRef, currentSerial: Int): Props = Props(classOf[Receiver], target, currentSerial)

  // Internal messages
  case class Message(msg: Any, sender: ActorRef, serial: Int)
  private case class Ack(serial: Int)
  private case object Tick
  private case object ReconnectTick

  /**
   * `TargetChanged` is sent to transition subscribers when the target `ActorRef` has
   * changed (for example, the target system crashed and has been restarted).
   */
  case class TargetChanged(ref: ActorRef)

  /**
   * `ProxyTerminated` is sent to transition subscribers during `postStop`.  Any outstanding
   * unsent messages are contained the `Unsent` object.
   */
  case class ProxyTerminated(actor: ActorRef, outstanding: Unsent)
  case class Unsent(queue: Vector[Message])

  sealed trait State
  case object Idle extends State
  case object Active extends State
  case object Reconnect extends State

  // Java API
  val idle = Idle
  val active = Active
  val reconnect = Reconnect

  trait DebugLogging extends ActorLogging { this: Actor ⇒
    val debug =
      Try(context.system.settings.config.getBoolean("akka.actor.debug.reliable-proxy")) getOrElse false

    def enabled = debug && log.isDebugEnabled

    def addSelf(template: String) = s"$template [$self]"

    def logDebug(template: String, arg1: Any, arg2: Any): Unit =
      if (enabled) log.debug(addSelf(template), arg1, arg2)

    def logDebug(template: String, arg1: Any): Unit =
      if (enabled) log.debug(addSelf(template), arg1)
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
 * of the reliable “tunnel”, which usually spans an unreliable network.
 *
 * Note that the ReliableProxy guarantees at-least-once, not exactly-once, delivery.
 *
 * Delivery from the remote end-point to the target actor is still subject to in-JVM
 * delivery semantics (i.e. not strictly guaranteed due to possible OutOfMemory
 * situations or other VM errors).
 *
 * You can create a reliable connection like this:
 *
 * In Scala:
 * {{{
 * val proxy = context.actorOf(ReliableProxy.props(target, 100.millis, 120.seconds)
 * }}}
 * or in Java:
 * {{{
 * final ActorRef proxy = getContext().actorOf(ReliableProxy.props(
 *   target, Duration.create(100, "millis"), Duration.create(120, "seconds")));
 * }}}
 *
 * '''''Please note:''''' the tunnel is uni-directional, and original sender
 * information is retained, hence replies by the wrapped target reference will
 * go back in the normal “unreliable” way unless also secured by a ReliableProxy
 * from the remote end.
 *
 * ==Message Types==
 *
 * This actor is an [[akka.actor.FSM]], hence it offers the service of
 * transition callbacks to those actors which subscribe using the
 * ``SubscribeTransitionCallBack`` and ``UnsubscribeTransitionCallBack``
 * messages; see [[akka.actor.FSM]] for more documentation. The proxy will
 * transition into [[ReliableProxy.Active]] state when ACKs
 * are outstanding and return to the [[ReliableProxy.Idle]]
 * state when every message send so far has been confirmed by the peer end-point.
 *
 * If a communication failure causes the tunnel to terminate via Remote Deathwatch
 * the proxy will transition into [[ReliableProxy.Reconnect]]
 * state. In this state the proxy will repeatedly send [[akka.actor.Identify]] messages
 * to ActorSelection(target.path) in order to obtain a new ActorRef for the target.
 * When an [[akka.actor.ActorIdentity]] for the target is received a new tunnel will
 * be created and the proxy will transition to either `Active` or `Idle`, depending
 * if there are any outstanding messages.  If the target ActorRef has changed a
 * [[ReliableProxy.TargetChanged]] message will be sent to the
 * proxy's transition subscribers.
 *
 * If `maxReconnects` is defined and the maximum number of reconnections is reached,
 * this actor will stop itself.
 *
 * If this actor is stopped and it still has outstanding messages a
 * [[ReliableProxy.ProxyTerminated]] message will be sent to the
 * transition subscribers.  It contains an `Unsent` object with the outstanding messages.
 *
 * If an [[ReliableProxy.Unsent]] message is sent to this actor
 * the messages contained within it will be relayed through the tunnel to the target.
 *
 * Any other message type sent to this actor will be delivered via a remote-deployed
 * child actor to the designated target.
 *
 * ==Failure Cases==
 *
 * All failures of either the local or the remote end-point are escalated to the
 * parent of this actor; there are no specific error cases which are predefined.
 *
 * ==Arguments==
 * See the constructor below for the arguments for this actor.  However, prefer using
 * [[akka.contrib.pattern.ReliableProxy#props]] to this actor's constructor as the `props`
 * methods are simpler.
 *
 * @param target is the actor to which all messages will be forwarded which are sent to
 *   this actor. It is either an `ActorRef` or an `ActorPath`.  In the case of an
 *   `ActorPath` this actor will start in the `Reconnect` state and will attempt to
 *   connect to the remote actor as described above. ``target`` can be a local or remote
 *   actor, but the “remote” tunnel endpoint will be deployed on the node where the
 *   target actor lives.
 * @param retryAfter is the ACK timeout after which all outstanding messages
 *   will be resent. There is no limit on the queue size or the number of retries.
 * @param reconnectAfter &nbsp;is an optional interval between reconnection attempts after
 *   the target is terminated.  The minimum recommended value for this is **TODO**.
 *   Use `None` to never reconnect.
 * @param maxReconnects &nbsp;is an optional maximum number of attempts to reconnect.
 *   Use `None` for no limit. If `reconnectAfter` is `None` this value is ignored.
 */
class ReliableProxy(target: Either[ActorRef, ActorPath], retryAfter: FiniteDuration,
                    reconnectAfter: Option[FiniteDuration], maxReconnects: Option[Int])
  extends Actor with LoggingFSM[State, Vector[Message]] with DebugLogging {

  var tunnel: ActorRef = _
  var currentSerial: Int = 0
  var lastAckSerial: Int = _
  var currentTarget: ActorRef = _
  var attemptedReconnects: Int = _

  def createTunnel(target: ActorRef): Unit = {
    logDebug("Creating new tunnel for {}", target)
    tunnel = context.actorOf(receiver(target, lastAckSerial).
      withDeploy(Deploy(scope = RemoteScope(target.path.address))), "tunnel")

    context.watch(tunnel)
    currentTarget = target
    attemptedReconnects = 0
    resetBackoff()
  }

  val (targetPath, initialState) = target match {
    case Right(path) ⇒
      self ! ReconnectTick
      (path, Reconnect)
    case Left(ref) ⇒
      createTunnel(ref)
      (ref.path, Idle)
  }

  if (targetPath.address.host.isEmpty && self.path.address == targetPath.address) {
    logDebug("Unnecessary to use ReliableProxy for local target: {}", target)
  }

  val resendTimer = "resend"
  val reconnectTimer = "reconnect"

  override def supervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }

  override def postStop() {
    logDebug("Stopping proxy and sending {} messages to parent in Unsent", stateData.size)
    gossip(ProxyTerminated(self, Unsent(stateData)))
    super.postStop()
  }

  startWith(initialState, Vector.empty)

  when(Idle) {
    case Event(Terminated(_), _) ⇒ terminated()
    case Event(Ack(_), _)        ⇒ stay()
    case Event(Unsent(msgs), _)  ⇒ goto(Active) using resend(updateSerial(msgs))
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
      if (compare(serial, lastAckSerial) > 0) lastAckSerial = serial
      scheduleTick()
      if (q.isEmpty) goto(Idle) using Vector.empty
      else stay using q
    case Event(Tick, queue) ⇒
      logResend(queue.size)
      queue foreach { tunnel ! _ }
      scheduleTick()
      stay()
    case Event(Unsent(msgs), queue) ⇒
      stay using queue ++ resend(updateSerial(msgs))
    case Event(msg, queue) ⇒
      stay using (queue :+ send(msg, sender))
  }

  when(Reconnect) {
    case Event(Terminated(_), _) ⇒
      stay()
    case Event(ActorIdentity(_, Some(actor)), queue) ⇒
      val curr = currentTarget
      cancelTimer(reconnectTimer)
      createTunnel(actor)
      if (currentTarget != curr) gossip(TargetChanged(currentTarget))
      if (queue.isEmpty) goto(Idle) else goto(Active) using resend(queue)
    case Event(ActorIdentity(_, None), _) ⇒
      stay()
    case Event(ReconnectTick, _) ⇒
      if (maxReconnects exists (_ == attemptedReconnects)) {
        logDebug("Failed to reconnect after {}", attemptedReconnects)
        stop()
      } else {
        logDebug("{} ! {}", context.actorSelection(targetPath), Identify(targetPath))
        context.actorSelection(targetPath) ! Identify(targetPath)
        scheduleReconnectTick()
        attemptedReconnects += 1
        stay()
      }
    case Event(Unsent(msgs), queue) ⇒
      stay using queue ++ updateSerial(msgs)
    case Event(msg, queue) ⇒
      stay using (queue :+ Message(msg, sender, nextSerial()))
  }

  def scheduleTick(): Unit = setTimer(resendTimer, Tick, retryAfter, repeat = false)

  def nextSerial(): Int = {
    currentSerial += 1
    currentSerial
  }

  def send(msg: Any, snd: ActorRef): Message = {
    val m = Message(msg, snd, nextSerial())
    tunnel ! m
    m
  }

  def updateSerial(q: Vector[Message]) = q map (_.copy(serial = nextSerial()))

  def resend(q: Vector[Message]): Vector[Message] = {
    logResend(q.size)
    q foreach { tunnel ! _ }
    q
  }

  def logResend(size: Int): Unit =
    logDebug("Resending {} messages through tunnel", size)

  def terminated(): State = {
    logDebug("Terminated: {}", target)
    if (reconnectAfter.isDefined) goto(Reconnect)
    else stop()
  }

  def scheduleReconnectTick(): Unit = {
    val delay = nextBackoff()
    logDebug("Will attempt to reconnect to {} in {}", targetPath, delay)
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
  def nextBackoff(): FiniteDuration = reconnectAfter getOrElse
    (throw new IllegalStateException("nextBackoff called and reconnectAfter is undefined"))
}