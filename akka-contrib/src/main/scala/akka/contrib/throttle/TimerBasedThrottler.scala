/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.throttle

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.control.NonFatal
import scala.collection.immutable.{ Queue ⇒ Q }
import akka.actor.{ ActorRef, Actor, FSM }
import Throttler._
import TimerBasedThrottler._
import java.util.concurrent.TimeUnit
import akka.AkkaException

/**
 * Marker trait for throttlers.
 *
 * == Throttling ==
 * A <em>throttler</em> is an actor that is defined through a <em>target actor</em> and a <em>rate</em>
 * (of type [[akka.contrib.throttle.Throttler.Rate]]). You set or change the target and rate at any time through the `SetTarget(target)`
 * and `SetRate(rate)` messages, respectively. When you send the throttler any other message `msg`, it will
 * put the message `msg` into an internal queue and eventually send all queued messages to the target, at
 * a speed that respects the given rate. If no target is currently defined then the messages will be queued
 * and will be delivered as soon as a target gets set.
 *
 * A [[akka.contrib.throttle.Throttler]] understands actor messages of type
 * [[akka.contrib.throttle.Throttler.SetTarget]], [[akka.contrib.throttle.Throttler.SetRate]], in
 * addition to any other messages, which the throttler will consider as messages to be sent to
 * the target.
 *
 * == Transparency ==
 * Notice that the throttler `forward`s messages, i.e., the target will see the original message sender (and not the throttler) as the sender of the message.
 *
 * == Persistence ==
 * Throttlers usually use an internal queue to keep the messages that need to be sent to the target.
 * You therefore cannot rely on the throttler's inbox size in order to learn how much messages are
 * outstanding.
 *
 * It is left to the implementation whether the internal queue is persisted over application restarts or
 * actor failure.
 *
 * == Processing messages ==
 * The target should process messages as fast as possible. If the target requires substantial time to
 * process messages, it should distribute its work to other actors (using for example something like
 * a `BalancingDispatcher`), otherwise the resulting system will always work <em>below</em>
 * the threshold rate.
 *
 * <em>Example:</em> Suppose the throttler has a rate of 3msg/s and the target requires 1s to process a message.
 * This system will only process messages at a rate of 1msg/s: the target will receive messages at at most 3msg/s
 * but as it handles them synchronously and each of them takes 1s, its inbox will grow and grow. In such
 * a situation, the target should <em>distribute</em> its messages to a set of worker actors so that individual messages
 * can be handled in parallel.
 *
 * @see [[akka.contrib.throttle.TimerBasedThrottler]]
 */
trait Throttler { self: Actor ⇒ }

/**
 * Message types understood by [[akka.contrib.throttle.Throttler]]'s.
 *
 * @see [[akka.contrib.throttle.Throttler]]
 * @see [[akka.contrib.throttle.Throttler.Rate]]
 */
object Throttler {
  /**
   * A rate used for throttling.
   *
   * There are some shorthands available to construct rates:
   * {{{
   *  import java.util.concurrent.TimeUnit._
   *  import scala.concurrent.duration.{ Duration, FiniteDuration }
   *
   *  val rate1 = 1 msgsPer (1, SECONDS)
   *  val rate2 = 1 msgsPer Duration(1, SECONDS)
   *  val rate3 = 1 msgsPer (1 seconds)
   *  val rate4 = 1 msgsPerSecond
   *  val rate5 = 1 msgsPerMinute
   *  val rate6 = 1 msgsPerHour
   * }}}
   *
   * @param numberOfCalls the number of calls that may take place in a period
   * @param duration the length of the period
   * @see [[akka.contrib.throttle.Throttler]]
   */
  case class Rate(val numberOfCalls: Int, val duration: FiniteDuration) {
    /**
     * The duration in milliseconds.
     */
    def durationInMillis(): Long = duration.toMillis
  }

  /**
   * Set the target of a [[akka.contrib.throttle.Throttler]].
   *
   * You may change a throttler's target at any time.
   *
   * Notice that the messages sent by the throttler to the target will have the original sender (and
   * not the throttler) as the sender. (In Akka terms, the throttler `forward`s the message.)
   *
   * @param target if `target` is `None`, the throttler will stop delivering messages and the messages already received
   *  but not yet delivered, as well as any messages received in the future will be queued
   *  and eventually be delivered when a new target is set. If `target` is not `None`, the currently queued messages
   *  as well as any messages received in the the future will be delivered to the new target at a rate not exceeding the current throttler's rate.
   */
  case class SetTarget(target: Option[ActorRef])

  /**
   * Set the rate of a [[akka.contrib.throttle.Throttler]].
   *
   * You may change a throttler's rate at any time.
   *
   * @param rate the rate at which messages will be delivered to the target of the throttler
   */
  case class SetRate(rate: Rate)

  import language.implicitConversions

  /**
   * Helper for some syntactic sugar.
   *
   * @see [[akka.contrib.throttle.Throttler.Rate]]
   */
  implicit class RateInt(val numberOfCalls: Int) extends AnyVal {
    def msgsPer(duration: Int, timeUnit: TimeUnit) = Rate(numberOfCalls, Duration(duration, timeUnit))
    def msgsPer(duration: FiniteDuration) = Rate(numberOfCalls, duration)
    def msgsPerSecond = Rate(numberOfCalls, Duration(1, TimeUnit.SECONDS))
    def msgsPerMinute = Rate(numberOfCalls, Duration(1, TimeUnit.MINUTES))
    def msgsPerHour = Rate(numberOfCalls, Duration(1, TimeUnit.HOURS))
  }

}

/**
 * Implementation-specific internals.
 */
object TimerBasedThrottler {
  private[throttle] case object Tick

  // States of the FSM: A `TimerBasedThrottler` is in state `Active` iff the timer is running.
  private[throttle] sealed trait State
  private[throttle] case object Idle extends State
  private[throttle] case object Active extends State

  // Messages, as we queue them to be sent later
  private[throttle] case class Message(message: Any, sender: ActorRef)

  // The data of the FSM
  private[throttle] sealed case class Data(target: Option[ActorRef],
                                           callsLeftInThisPeriod: Int,
                                           queue: Q[Message])
}

/**
 * A [[akka.contrib.throttle.Throttler]] that uses a timer to control the message delivery rate.
 *
 * ==Example==
 * For example, if you set a rate like "3 messages in 1 second", the throttler
 * will send the first three messages immediately to the target actor but will need to impose a delay before
 * sending out further messages:
 * {{{
 *   // A simple actor that prints whatever it receives
 *   val printer = system.actorOf(Props(new Actor {
 *     def receive = {
 *       case x => println(x)
 *     }
 *   }))
 *   // The throttler for this example, setting the rate
 *   val throttler = system.actorOf(Props(new TimerBasedThrottler(3 msgsPer (1.second))))
 *   // Set the target
 *   throttler ! SetTarget(Some(printer))
 *   // These three messages will be sent to the printer immediately
 *   throttler ! "1"
 *   throttler ! "2"
 *   throttler ! "3"
 *   // These two will wait at least until 1 second has passed
 *   throttler ! "4"
 *   throttler ! "5"
 * }}}
 *
 * ==Implementation notes==
 * This throttler implementation internally installs a timer that repeats every `rate.durationInMillis` and enables `rate.numberOfCalls`
 * additional calls to take place. A `TimerBasedThrottler` uses very few system resources, provided the rate's duration is not too
 * fine-grained (which would cause a lot of timer invocations); for example, it does not store the calling history
 * as other throttlers may need to do.
 *
 * However, a `TimerBasedThrottler` only provides ''weak guarantees'' on the rate (see also
 * <a href='http://letitcrash.com/post/28901663062/throttling-messages-in-akka-2'>this blog post</a>):
 *
 *  - Only ''delivery'' times are taken into account: if, for example, the throttler is used to throttle
 *    requests to an external web service then only the start times of the web requests are considered.
 *    If a web request takes very long on the server then more than `rate.numberOfCalls`-many requests
 *    may be observed on the server in an interval of duration `rate.durationInMillis()`.
 *  - There may be intervals of duration `rate.durationInMillis()` that contain more than `rate.numberOfCalls`
 *    message deliveries: a `TimerBasedThrottler` only makes guarantees for the intervals
 *    of its ''own'' timer, namely that no more than `rate.numberOfCalls`-many messages are delivered within such intervals. Other intervals on the
 *    timeline may contain more calls.
 *
 * For some applications, these guarantees may not be sufficient.
 *
 * ==Known issues==
 *
 *  - If you change the rate using `SetRate(rate)`, the actual rate may in fact be higher for the
 *    overlapping period (i.e., `durationInMillis()`) of the new and old rate. Therefore,
 *    changing the rate frequently is not recommended with the current implementation.
 *  - The queue of messages to be delivered is not persisted in any way; actor or system failure will
 *    cause the queued messages to be lost.
 *
 * @see [[akka.contrib.throttle.Throttler]]
 */
class TimerBasedThrottler(var rate: Rate) extends Actor with Throttler with FSM[State, Data] {
  startWith(Idle, Data(None, rate.numberOfCalls, Q()))

  // Idle: no messages, or target not set
  when(Idle) {
    // Set the rate
    case Event(SetRate(rate), d) ⇒
      this.rate = rate
      stay using d.copy(callsLeftInThisPeriod = rate.numberOfCalls)

    // Set the target
    case Event(SetTarget(t @ Some(_)), d) if !d.queue.isEmpty ⇒
      goto(Active) using deliverMessages(d.copy(target = t))
    case Event(SetTarget(t), d) ⇒
      stay using d.copy(target = t)

    // Queuing
    case Event(msg, d @ Data(None, _, queue)) ⇒
      stay using d.copy(queue = queue.enqueue(Message(msg, context.sender)))
    case Event(msg, d @ Data(Some(_), _, Seq())) ⇒
      goto(Active) using deliverMessages(d.copy(queue = Q(Message(msg, context.sender))))
    // Note: The case Event(msg, t @ Data(Some(_), _, _, Seq(_*))) should never happen here.
  }

  when(Active) {
    // Set the rate
    case Event(SetRate(rate), d) ⇒
      this.rate = rate
      // Note: this should be improved (see "Known issues" in class comments)
      stopTimer()
      startTimer(rate)
      stay using d.copy(callsLeftInThisPeriod = rate.numberOfCalls)

    // Set the target (when the new target is None)
    case Event(SetTarget(None), d) ⇒
      // Note: We do not yet switch to state `Inactive` because we need the timer to tick once more before
      stay using d.copy(target = None)

    // Set the target (when the new target is not None)
    case Event(SetTarget(t @ Some(_)), d) ⇒
      stay using d.copy(target = t)

    // Tick after a `SetTarget(None)`: take the additional permits and go to `Idle`
    case Event(Tick, d @ Data(None, _, _)) ⇒
      goto(Idle) using d.copy(callsLeftInThisPeriod = rate.numberOfCalls)

    // Period ends and we have no more messages: take the additional permits and go to `Idle`
    case Event(Tick, d @ Data(_, _, Seq())) ⇒
      goto(Idle) using d.copy(callsLeftInThisPeriod = rate.numberOfCalls)

    // Period ends and we get more occasions to send messages
    case Event(Tick, d @ Data(_, _, _)) ⇒
      stay using deliverMessages(d.copy(callsLeftInThisPeriod = rate.numberOfCalls))

    // Queue a message (when we cannot send messages in the current period anymore)
    case Event(msg, d @ Data(_, 0, queue)) ⇒
      stay using d.copy(queue = queue.enqueue(Message(msg, context.sender)))

    // Queue a message (when we can send some more messages in the current period)
    case Event(msg, d @ Data(_, _, queue)) ⇒
      stay using deliverMessages(d.copy(queue = queue.enqueue(Message(msg, context.sender))))
  }

  onTransition {
    case Idle -> Active ⇒ startTimer(rate)
    case Active -> Idle ⇒ stopTimer()
  }

  initialize

  private def startTimer(rate: Rate) = setTimer("morePermits", Tick, rate.duration, true)
  private def stopTimer() = cancelTimer("morePermits")

  /**
   * Send as many messages as we can (while respecting the rate) to the target and
   * return the state data (with the queue containing the remaining ones).
   */
  private def deliverMessages(data: Data): Data = {
    val queue = data.queue
    val nrOfMsgToSend = scala.math.min(queue.length, data.callsLeftInThisPeriod)

    queue.take(nrOfMsgToSend).foreach(x ⇒ data.target.get.tell(x.message, x.sender))

    data.copy(queue = queue.drop(nrOfMsgToSend), callsLeftInThisPeriod = data.callsLeftInThisPeriod - nrOfMsgToSend)
  }
}