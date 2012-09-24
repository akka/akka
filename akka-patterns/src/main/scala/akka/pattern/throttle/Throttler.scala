package akka.pattern.throttle

import java.util.concurrent.TimeUnit
import scala.concurrent.util.Duration
import akka.actor.{ Actor, ActorRef }
import akka.AkkaException

/**
 * Marker trait for throttlers.
 *
 * == Throttling ==
 * A [[akka.pattern.throttle.Throttler]] understands actor messages of type
 * [[akka.pattern.throttle.Throttler.SetTarget]], [[akka.pattern.throttle.Throttler.SetRate]], in
 * addition to any other messages, which the throttler will consider as messages to be sent to
 * the target.
 *
 * A <em>throttler</em> is an actor that is defined through a <em>target actor</em> and a <em>rate</em>
 * (of type [[akka.pattern.throttle.Throttler.Rate]]). You set or change the target and rate at any time through the `SetTarget(target)`
 * and `SetRate(rate)` messages, respectively. When you send the throttler any other message `msg`, it will
 * put the message `msg` into an internal queue and eventually send all queued messages to the target, at
 * a speed that respects the given rate. If no target is currently defined then the messages will be queued
 * and will be delivered as soon as a target gets set.
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
 * a [[akka.dispatch.BalancingDispatcher]]), otherwise the resulting system will always work <em>below</em>
 * the threshold rate.
 *
 * <em>Example:</em> Suppose the throttler has a rate of 3msg/s and the target requires 1s to process a message.
 * This system will only process messages at a rate of 1msg/s: the target will receive messages at at most 3msg/s
 * but as it handles them synchronously and each of them takes 1s, its inbox will grow and grow and it will lag more and more behind. In such
 * a situation, the target should <em>distribute</em> its messages to a set of worker actors so that individual messages
 * can be handled in parallel.
 *
 * @see [[akka.pattern.throttle.TimerBasedThrottler]]
 */
trait Throttler { self: Actor ⇒ }

/**
 * Implicits for a few nice DSL-like constructs.
 *
 * @see [[akka.pattern.throttle.Throttler.Rate]]
 */
object Throttler {
  /**
   * A rate used for throttling.
   *
   * There are some shorthands available to construct rates:
   * {{{
   *  import java.util.concurrent.TimeUnit._
   *  import akka.pattern.throttle.Rate
   *  import akka.pattern.throttle.Throttler._
   *  import scala.concurrent.util.Duration
   *
   *  val rate1 = 1 msgsPer (1, SECONDS)
   *  val rate2 = 1 msgsPer Duration(1, SECONDS)
   *  val rate3 = 1 msgsPerSecond
   *  val rate4 = 1 msgsPerMinute
   *  val rate5 = 1 msgsPerHour
   * }}}
   *
   * @param numberOfCalls the number of calls that may take place in a period
   * @param duration the length of the period
   * @param timeUnit the time-unit used to express the duration
   * @see [[akka.pattern.throttle.Throttler]]
   */
  case class Rate(val numberOfCalls: Int, val duration: Duration) {
    /**
     * The duration in milliseconds.
     */
    def durationInMillis(): Long = duration.toMillis
  }

  /**
   * Set the target of a [[akka.pattern.throttle.Throttler]].
   *
   * You may change a throttler's target at any time.
   *
   * Notice that the messages sent by the throttler to the target will have the original sender (and
   * not the throttler) as the sender. (In Akka terms, the throttler `forward`s the message.)
   *
   * @param target if `target` is `None`, the throttler will stop delivering messages and the messages already received
   *  but not yet delivered, as well as any messages received in the future, will be queued
   *  and eventually be delivered when a new target is set. If `target` is defined, the currently queued messages
   *  as well as any messages received in the the future will be delivered to the new target at a rate not exceeding the current throttler's rate.
   */
  case class SetTarget(target: Option[ActorRef])

  /**
   * Set the rate of a [[akka.pattern.throttle.Throttler]].
   *
   * You may change a throttler's rate at any time.
   *
   * @param rate the rate at which messages will be delivered to the target of the throttler
   */
  case class SetRate(rate: Rate)

  /**
   * @see [[akka.pattern.throttle.Throttler.Rate]]
   */
  class RateInt(numberOfCalls: Int) {
    def msgsPer(duration: Int, timeUnit: TimeUnit) = Rate(numberOfCalls, Duration(duration, timeUnit))
    def msgsPer(duration: Duration) = Rate(numberOfCalls, duration)
    def msgsPerSecond = Rate(numberOfCalls, Duration(1, TimeUnit.SECONDS))
    def msgsPerMinute = Rate(numberOfCalls, Duration(1, TimeUnit.MINUTES))
    def msgsPerHour = Rate(numberOfCalls, Duration(1, TimeUnit.HOURS))
  }

  import language.implicitConversions

  /**
   * @see [[akka.pattern.throttle.Throttler.Rate]]
   */
  implicit def toRateInt(numberOfCalls: Int) = new RateInt(numberOfCalls)
}
