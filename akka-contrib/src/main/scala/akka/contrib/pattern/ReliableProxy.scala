/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.actor._
import akka.remote.RemoteScope
import scala.concurrent.util._

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
      case Terminated(`target`) ⇒ context stop self
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

  def receiver(target: ActorRef): Props = Props(new Receiver(target))

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
 * This actor is an [[akka.actor.FSM]], hence it offers the service of
 * transition callbacks to those actors which subscribe using the
 * ``SubscribeTransitionCallBack`` and ``UnsubscribeTransitionCallBack``
 * messages; see [[akka.actor.FSM]] for more documentation. The proxy will
 * transition into [[ReliableProxy.Active]] state when ACKs are outstanding and
 * return to the [[ReliableProxy.Idle]] state when every message send so far
 * has been confirmed by the peer end-point.
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
 * will be resent. There is not limit on the queue size or the number of retries.
 */
class ReliableProxy(target: ActorRef, retryAfter: FiniteDuration) extends Actor with FSM[State, Vector[Message]] {

  val tunnel = context.actorOf(receiver(target).withDeploy(Deploy(scope = RemoteScope(target.path.address))), "tunnel")
  context.watch(tunnel)

  override def supervisorStrategy = OneForOneStrategy() {
    case _ ⇒ SupervisorStrategy.Escalate
  }

  startWith(Idle, Vector.empty)

  when(Idle) {
    case Event(Terminated(`tunnel`), _) ⇒ stop
    case Event(Ack(_), _)               ⇒ stay
    case Event(msg, _)                  ⇒ goto(Active) using Vector(send(msg))
  }

  onTransition {
    case Idle -> Active ⇒ scheduleTick()
    case Active -> Idle ⇒ cancelTimer("resend")
  }

  when(Active) {
    case Event(Terminated(`tunnel`), _) ⇒ stop
    case Event(Ack(serial), queue) ⇒
      val q = queue dropWhile (m ⇒ compare(m.serial, serial) <= 0)
      scheduleTick()
      if (q.isEmpty) goto(Idle) using Vector.empty
      else stay using q
    case Event(Tick, queue) ⇒
      queue foreach (tunnel ! _)
      scheduleTick()
      stay
    case Event(msg, queue) ⇒ stay using (queue :+ send(msg))
  }

  def scheduleTick(): Unit = setTimer("resend", Tick, retryAfter, false)

  var nextSerial = 1
  def send(msg: Any): Message = {
    val m = Message(msg, sender, nextSerial)
    nextSerial += 1
    tunnel ! m
    m
  }

}