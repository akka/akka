/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.actor._
import akka.remote.RemoteScope
import scala.concurrent.duration._

object ReliableProxy {

  class Receiver(target: ActorRef) extends Actor with ActorLogging {
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
          log.debug("received msg of {} from {} with wrong serial", msg.asInstanceOf[AnyRef].getClass, snd)
        }
      //TODO use exact match of target when all actor references have uid, i.e. actorFor has been removed
      case Terminated(a) if a.path == target.path ⇒ context stop self
    }
  }

  /**
   * Wrap-around aware comparison of integers: differences limited to 2^31-1
   * in magnitude will work correctly.
   */
  def compare(a: Int, b: Int): Int = (a - b) match {
    case x if x < 0  ⇒ -1
    case x if x == 0 ⇒ 0
    case x if x > 0  ⇒ 1
  }

  case class Message(msg: Any, sender: ActorRef, serial: Int)
  case class Ack(serial: Int)
  case object Tick

  def receiver(target: ActorRef): Props = Props(classOf[Receiver], target)

  sealed trait State
  case object Idle extends State
  case object Active extends State

  // Java API
  val idle = Idle
  val active = Active
}

import ReliableProxy._

/**
 * A ReliableProxy is a means to wrap a remote actor reference in order to
 * obtain certain improved delivery guarantees:
 *
 *  - as long as none of the JVMs crashes and the proxy and its remote-deployed
 *    peer are not forcefully terminated or restarted, no messages will be lost
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
 *
 * In Scala:
 *
 * {{{
 * val proxy = context.actorOf(Props(classOf[ReliableProxy], target))
 * }}}
 *
 * In Java:
 *
 * {{{
 * final ActorRef proxy = getContext().actorOf(Props.create(ReliableProxy.class target));
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
 * transition into [[ReliableProxy.Active]] state when ACKs are outstanding and
 * return to the [[ReliableProxy.Idle]] state when every message send so far
 * has been confirmed by the peer end-point.
 * If a Unsent message is sent while the proxy is in ReliableProxy.Idle state the
 * contained messages will be relayed through the tunnel to the target.  See the
 * docs for Unsent for more details.
 * Any other message type sent to this actor will be delivered via a remote-deployed
 * child actor to the designated target. Message types declared in the companion
 * object are for internal use only and not to be sent from the outside.
 *
 * ==Failure Cases==
 *
 * All failures of either the local or the remote end-point are escalated to the
 * parent of this actor; there are no specific error cases which are predefined.
 *
 * ==Arguments==
 *
 * '''''target''''' is the [[akka.actor.ActorRef]] to which all messages will be
 * forwarded which are sent to this actor. It can be any type of actor reference,
 * but the “remote” tunnel endpoint will be deployed on the node where the target
 * ref points to.
 *
 * '''''retryAfter''''' is the ACK timeout after which all outstanding messages
 * will be resent. There is no limit on the queue size.
 *
 * '''''maxRetries''''' is the maximum number of retries (0 means no maximum).
 * After the maxRetries has been reached the actor will stop itself and send a
 * ProxyTerminated message to its parent.
 */
class ReliableProxy(target: ActorRef, retryAfter: FiniteDuration, maxRetries: Int = 0)
  extends Actor with FSM[State, Vector[Message]] {

  val tunnel = context.actorOf(receiver(target).withDeploy(Deploy(scope = RemoteScope(target.path.address))), "tunnel")
  context.watch(tunnel)

  var numRetries = 0

  override def supervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }

  startWith(Idle, Vector.empty)

  when(Idle) {
    case Event(Terminated(`tunnel`), _) ⇒ stop()
    case Event(Ack(_), _)               ⇒ stay()
    case Event(Unsent(queue), _)        ⇒ goto(Active) using resend(queue)
    case Event(msg, _)                  ⇒ goto(Active) using Vector(send(msg, sender))
  }

  onTransition {
    case Idle -> Active ⇒ scheduleTick()
    case Active -> Idle ⇒ cancelTimer("resend")
  }

  when(Active) {
    case Event(Terminated(`tunnel`), _) ⇒ stop()
    case Event(Ack(serial), queue) ⇒
      val q = queue dropWhile (m ⇒ compare(m.serial, serial) <= 0)
      scheduleTick()
      numRetries = 0
      if (q.isEmpty) goto(Idle) using Vector.empty
      else stay using q
    case Event(Tick, queue) ⇒
      if (maxRetries > 0 && numRetries >= maxRetries) {
        log.warning("Stopping proxy after " + numRetries + " retries. Proxy=" + self)
        context.parent ! ProxyTerminated(self, Unsent(queue))
        stop()
      } else {
        log.warning("Resending " + queue.size + " messages through tunnel. Proxy=" + self)
        queue foreach (tunnel ! _)
        scheduleTick()
        numRetries += 1
        stay()
      }
    case Event(msg, queue) ⇒ stay using (queue :+ send(msg, sender))
  }

  def scheduleTick(): Unit = setTimer("resend", Tick, retryAfter, repeat = false)

  var nextSerial = 1
  def send(msg: Any, sender: ActorRef): Message = {
    val m = Message(msg, sender, nextSerial)
    nextSerial += 1
    tunnel ! m
    m
  }

  def resend(q: Vector[Message]): Vector[Message] =
    q map { case Message(msg, sender, _) ⇒ send(msg, sender) }
}

/**
 * If the JVM running the tunnel (Receiver) crashes or is killed then DeathWatch
 * will not send Terminated messages when the ReliableProxy is stopped
 * (because it is unable to stop its child tunnel).
 *
 * In addition, if the JVM that was running the tunnel is then restarted,
 * the tunnel will not exist in the new instance of the JVM.
 *
 * To mitigate these problems the ReliableProxy will send a ProxyTerminated
 * message to its parent during postStop() to alert the parent of its death.
 * It includes the unsent messages in case the parent wants to recreate the
 * proxy (and tunnel) and resend the unsent messages (in this case, send the
 * Unsent object itself).
 */
case class ProxyTerminated(actor: ActorRef, outstanding: Unsent)

/**
 * Sent to a ReliableProxy's parent as part of a ProxyTerminated message
 * or directly to a ReliableProxySuper's parent when its underlying ReliableProxy
 * has terminated.
 *
 * If this is sent back to the ReliableProxy/ReliableProxySuper the messages
 * contained in it will be resent through the new tunnel.  Note: this has
 * to be sent back to ReliableProxy/ReliableProxySuper before any other message.
 */
case class Unsent(private val queue: Vector[Message])

/**
 * ReliableProxySuper is an actor that both proxies the ReliableProxy and
 * supervises it, creating new ReliableProxy actors when they are terminated.
 *
 * When a ReliableProxy is terminated it sends a ProxyTerminated message to its
 * parent including the unsent messages.  If ``maxSuperRetries`` has not been
 * reached this actor will create a new ReliableProxy and send it the unsent
 * messages.
 *
 * If a ProxyTerminated message is received and ``maxSuperRetries`` has been
 * reached then this actor will send the Unsent messages to its parent and will
 * reset its internal ReliableProxy reference.  The next time this actor receives
 * a message it will create a new ReliableProxy.
 *
 * Note: Clients that want to take advantage of ReliableProxySuper should create
 * and send messages to ReliableProxySuper instead of creating and sending
 * messages to ReliableProxy.
 *
 * == Arguments ==
 *
 * This class takes the same arguments as ReliableProxy with one addition:
 *
 * ''''maxSuperRetries'''' is the number of times this actor will try to recreate
 * a ReliableProxy and send it outstanding messages after receiving a
 * ProxyTerminated message (0 means never recreate a ReliableProxy immediately
 * after receiving a ProxyTerminated message).  Note this setting does not prevent
 * this actor from recreating its ReliableProxy after it has propagated
 * ProxyTerminated to its parent.
 */
class ReliableProxySuper(target: ActorRef, retryAfter: FiniteDuration, maxRetries: Int = 0, maxSuperRetries: Int = 0)
  extends Actor with ActorLogging {

  var numRetries = 0
  var proxyCount = 0
  var proxy: Option[ActorRef] = Some(createProxy())

  override def supervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }

  def receive = {
    case ProxyTerminated(actor, unsent) if (proxy exists (_ == actor)) ⇒
      log.warning("ProxyTerminated: " + actor)
      if (maxSuperRetries >= 0 && numRetries >= maxSuperRetries) {
        proxy = None
        numRetries = 0
        context.parent ! unsent
      } else {
        val p = createProxy()
        proxy = Some(p)
        numRetries += 1
        p ! unsent
      }

    case msg ⇒
      if (proxy.isEmpty) proxy = Some(createProxy())
      proxy foreach (_.forward(msg))
  }

  def createProxy(): ActorRef = {
    val proxy = context.actorOf(Props(classOf[ReliableProxy], target, retryAfter, maxRetries), proxyName(target))
    log.info("Proxying " + target + " with " + proxy)
    proxy
  }

  def proxyName(ref: ActorRef): String = {
    val rpa = ref.path.address
    proxyCount += 1
    rpa.host.getOrElse(sys.error(ref + " is not a RemoteActorRef")) +
      "-" + rpa.port.getOrElse(0) + "-" + ref.path.name + "-" + proxyCount
  }
}
