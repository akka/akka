/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package sample.redelivery

import akka.actor._
import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom
import java.util.UUID

object SimpleOrderedRedeliverer {
  /**
   * Props for creating a [[SimpleOrderedRedeliverer]].
   */
  def props(retryTimeout: FiniteDuration) = Props(classOf[SimpleOrderedRedeliverer], retryTimeout)

  /*
   * Messages exchanged with the requester of the delivery.
   */
  case class Deliver(to: ActorRef, msg: Any, uuid: UUID)
  case class Delivered(uuid: UUID)
  case class AcceptedForDelivery(uuid: UUID)
  case class Busy(refused: UUID, currentlyProcessing: UUID)

  /*
   * Messages exchanged with the “deliveree”.
   */
  case class Ackable(from: ActorRef, msg: Any, uuid: UUID)
  case class Ack(uuid: UUID)

  /*
   * Various states the [[SimpleOrderedRedeliverer]] can be in.
   */
  sealed trait State
  case object Idle extends State
  case object AwaitingAck extends State

  sealed trait Data
  case object NoData extends Data

  /**
   * Keeps track of our last delivery request.
   */
  case class LastRequest(last: Deliver, requester: ActorRef) extends Data

  /**
   * Private message used only inside of the [[SimpleOrderedRedeliverer]] to signalize a tick of its retry timer.
   */
  private case object Retry
}

/**
 * An actor-in-the-middle kind. Takes care of message redelivery between two or more sides.
 *
 * Works “sequentially”, thus is able to process only one message at a time:
 *
 * <pre>
 *   Delivery-request#1 -> ACK#1 -> Delivery-request#2 -> ACK#2 -> ...
 * </pre>
 *
 * A situation like this:
 *
 * <pre>
 *   Delivery-request#1 -> Delivery-request#2 -> ...
 * </pre>
 *
 * ... will result in the second requester getting a [[SimpleOrderedRedeliverer.Busy]] message with [[UUID]]s
 * of both his request and currently-processed one.
 *
 * @param retryTimeout how long to wait for the [[SimpleOrderedRedeliverer.Ack]] message
 */
class SimpleOrderedRedeliverer(retryTimeout: FiniteDuration) extends Actor with FSM[SimpleOrderedRedeliverer.State, SimpleOrderedRedeliverer.Data] {
  import SimpleOrderedRedeliverer._

  // So that we don't make a typo when referencing this timer.
  val RetryTimer = "retry"

  // Start idle with neither last request, nor most recent requester.
  startWith(Idle, NoData)

  /**
   * Will process the provided request, sending an [[Ackable]] to its recipient and resetting the inner timer.
   * @return a new post-processing state.
   */
  def process(request: Deliver, requester: ActorRef): State = {
    request.to ! Ackable(requester, request.msg, request.uuid)
    setTimer(RetryTimer, Retry, retryTimeout, repeat = false)
    goto(AwaitingAck) using LastRequest(request, requester)
  }

  /*
   * When [[Idle]], accept new requests and process them, replying with [[WillTry]].
   */
  when(Idle) {
    case Event(request: Deliver, _) =>
      process(request, sender()) replying AcceptedForDelivery(request.uuid)
  }

  when(AwaitingAck) {

    /*
     * When awaiting the [[Ack]] and receiver seems not to have made it,
     * resend the message wrapped in [[Ackable]]. This time, however, without
     * sending [[WillTry]] to our requester!
     */
    case Event(Retry, LastRequest(request, requester)) =>
      process(request, requester)

    /*
     * Fortunately, the receiver made it! It his is an [[Ack]] of correct [[UUID]],
     * cancel the retry timer, notify original requester with [[Delivered]] message,
     * and turn [[Idle]] again.
     */
    case Event(Ack(uuid), LastRequest(request, requester)) if uuid == request.uuid =>
      cancelTimer(RetryTimer)
      requester ! Delivered(uuid)
      goto(Idle) using NoData

    /*
     * Someone (possibly else!) is trying to make the [[SimpleOrderedRedeliverer]] deliver a new message,
     * while an [[Ack]] for the last one has not yet been delivered. Reject.
     */
    case Event(request: Deliver, LastRequest(current, _)) =>
      stay() replying Busy(request.uuid, current.uuid)
  }

}

object Receiver {
  /**
   * Props for creating a [[Receiver]].
   */
  def props = Props(classOf[Receiver])
}

class Receiver extends Actor {
  /**
   * Simulate losing 75% of all messages on the receiving end. We want to see the redelivery in action!
   */
  def shouldSendAck = ThreadLocalRandom.current.nextDouble() < 0.25

  def receive = {
    case SimpleOrderedRedeliverer.Ackable(from, msg, uuid) =>
      val goingToSendAck = shouldSendAck
      println(s"""  [Receiver] got "$msg"; ${if (goingToSendAck) "" else " ***NOT***"} going to send Ack this time""")
      // Send a [[SimpleOrderedRedeliverer.Ack]] -- if they're lucky!
      if (goingToSendAck) sender() ! SimpleOrderedRedeliverer.Ack(uuid)
  }
}

object Requester {
  /**
   * Props for creating a [[Requester]].
   */
  def props = Props(classOf[Requester])

  /**
   * Requester-private message used to drive the simulation.
   */
  private case object Tick
}

class Requester extends Actor {
  import Requester._
  import context.dispatcher

  /*
   * Create a [[SimpleOrderedRedeliverer]] and a [[Receiver]].
   */
  val redeliverer = context.actorOf(SimpleOrderedRedeliverer.props(retryTimeout = 3.seconds))
  val receiver = context.actorOf(Receiver.props)

  /*
   * One message would be quite boring, let's pick a random of the three!
   */
  val messages = List("Hello!", "Ping!", "Howdy!")

  /*
   * Start ticking!
   */
  self ! Tick

  /**
   * Make a new request every anywhere-between-1-and-10 seconds.
   */
  def nextTickIn: FiniteDuration = (1.0 + ThreadLocalRandom.current.nextDouble() * 9.0).seconds

  def receive = {
    case Tick =>
      val msg = util.Random.shuffle(messages).head
      val uuid = UUID.randomUUID()
      println(s"""[Requester] requesting ("$msg", $uuid) to be sent to [Receiver]...""")

      /*
       * Make the actual request...
       */
      redeliverer ! SimpleOrderedRedeliverer.Deliver(receiver, msg, uuid)

      /*
       * ... and schedule a new [[Tick]].
       */
      context.system.scheduler.scheduleOnce(nextTickIn, self, Tick)

    /*
     * This case is used for displaying [[SimpleOrderedRedeliverer.WillTry]] and [[SimpleOrderedRedeliverer.Delivered]]
     * and [[SimpleOrderedRedeliverer.Busy]] messages.
     */
    case msg => println(s"[Requester] got $msg")
  }

}

object FsmSimpleRedelivery extends App {

  val system = ActorSystem()

  /*
   * Start a new [[Requester]] actor.
   */
  system.actorOf(Requester.props)

}
