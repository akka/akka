/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.dispatch.{ Promise }
import akka.actor._

/**
 * Actor pooling
 *
 * An actor pool is an message router for a set of delegate actors. The pool is an actor itself.
 * There are a handful of basic concepts that need to be understood when working with and defining your pool.
 *
 * Selectors - A selector is a trait that determines how and how many pooled actors will receive an incoming message.
 * Capacitors - A capacitor is a trait that influences the size of pool.  There are effectively two types.
 *                              The first determines the size itself - either fixed or bounded.
 *                              The second determines how to adjust of the pool according to some internal pressure characteristic.
 * Filters - A filter can be used to refine the raw pressure value returned from a capacitor.
 *
 * It should be pointed out that all actors in the pool are treated as essentially equivalent.  This is not to say
 * that one couldn't instance different classes within the pool, only that the pool, when selecting and routing,
 * will not take any type information into consideration.
 *
 * @author Garrick Evans
 */

object ActorPool {
  case object Stat
  case class Stats(size: Int)
}

/**
 * Defines the nature of an actor pool.
 */
trait ActorPool {
  /**
   * Adds a new actor to the pool. The DefaultActorPool implementation will start and link (supervise) this actor.
   * This method is invoked whenever the pool determines it must boost capacity.
   * @return A new actor for the pool
   */
  def instance(defaults: Props): ActorRef

  /**
   * This method gets called when a delegate is to be evicted, by default it sends a PoisonPill to the delegate
   */
  def evict(delegate: ActorRef): Unit = delegate ! PoisonPill

  /**
   * Returns the overall desired change in pool capacity. This method is used by non-static pools as the means
   * for the capacity strategy to influence the pool.
   * @param _delegates The current sequence of pooled actors
   * @return the number of delegates by which the pool should be adjusted (positive, negative or zero)
   */
  def capacity(delegates: Seq[ActorRef]): Int
  /**
   * Provides the results of the selector, one or more actors, to which an incoming message is forwarded.
   * This method returns an iterator since a selector might return more than one actor to handle the message.
   * You might want to do this to perform redundant processing of particularly error-prone messages.
   * @param _delegates The current sequence of pooled actors
   * @return a list of actors to which the message will be delivered
   */
  def select(delegates: Seq[ActorRef]): Seq[ActorRef]
}

/**
 * A default implementation of a pool that:
 *  First, invokes the pool's capacitor that tells it, based on the current delegate count
 *  and its own heuristic by how many delegates the pool should be resized.  Resizing can
 *  can be incremental, decremental or flat.  If there is a change to capacity, new delegates
 *  are added or existing ones are removed. Removed actors are sent the PoisonPill message.
 *  New actors are automatically started and linked.  The pool supervises the actors and will
 *  use the fault handling strategy specified by the mixed-in ActorPoolSupervisionConfig.
 *  Pooled actors may be any lifecycle. If you're testing pool sizes during runtime, take a
 *  look at the unit tests... Any delegate with a <b>Permanent</b> lifecycle will be
 *  restarted and the pool size will be level with what it was prior to the fault.  In just
 *  about every other case, e.g. the delegates are <b>Temporary</b> or the delegate cannot be
 *  restarted within the time interval specified in the fault handling strategy, the pool will
 *  be temporarily shy by that actor (it will have been removed by not back-filled).  The
 *  back-fill if any is required, will occur on the next message [as usual].
 *
 *  Second, invokes the pool's selector that returns a list of delegates that are to receive
 *  the incoming message.  Selectors may return more than one actor.  If <i>partialFill</i>
 *  is true then it might also the case that fewer than number of desired actors will be
 *  returned. If <i>partialFill</i> is false, the selector may return duplicate actors to
 *  reach the desired <i>selectionCount</i>.
 *
 *  Lastly, routes by forwarding the incoming message to each delegate in the selected set.
 */
trait DefaultActorPool extends ActorPool { this: Actor ⇒
  import ActorPool._

  protected[akka] var _delegates = Vector[ActorRef]()

  val defaultProps: Props = Props.default.withSupervisor(this.self).withDispatcher(this.context.dispatcher)

  override def postStop() {
    _delegates foreach evict
    _delegates = Vector.empty
  }

  protected def _route(): Receive = {
    // for testing...
    case Stat ⇒
      tryReply(Stats(_delegates length))
    case Terminated(victim, _) ⇒
      _delegates = _delegates filterNot { victim == }
    case msg ⇒
      resizeIfAppropriate()

      select(_delegates) foreach { _ forward msg }
  }

  private def resizeIfAppropriate() {
    val requestedCapacity = capacity(_delegates)
    val newDelegates = requestedCapacity match {
      case qty if qty > 0 ⇒
        _delegates ++ {
          for (i ← 0 until requestedCapacity) yield {
            val delegate = instance(defaultProps)
            self link delegate
            delegate
          }
        }
      case qty if qty < 0 ⇒
        _delegates.splitAt(_delegates.length + requestedCapacity) match {
          case (keep, abandon) ⇒
            abandon foreach evict
            keep
        }
      case _ ⇒ _delegates //No change
    }

    _delegates = newDelegates
  }
}

/**
 * Selectors
 *
 * These traits define how, when a message needs to be routed, delegate(s) are chosen from the pool.
 * Note that it's acceptable to return more than one actor to handle a given message.
 */

/**
 * Returns the set of delegates with the least amount of message backlog.
 */
trait SmallestMailboxSelector {
  /**
   * @return the number of delegates that will receive each message
   */
  def selectionCount: Int
  /**
   * If there aren't enough delegates to provide the selectionCount, either
   * send the message to fewer, or send the message selectionCount times
   * including more than once to some of the delegates. This setting does
   * not matter if you configure selectionCount to always be less than or
   * equal to the number of delegates in the pool.
   * @return true to send to fewer delegates or false to send to duplicate delegates
   */
  def partialFill: Boolean

  def select(delegates: Seq[ActorRef]): Seq[ActorRef] = {
    var set: Seq[ActorRef] = Nil
    var take = if (partialFill) math.min(selectionCount, delegates.length) else selectionCount

    def mailboxSize(a: ActorRef): Int = a match {
      case l: LocalActorRef ⇒ l.underlying.dispatcher.mailboxSize(l.underlying)
      case _                ⇒ Int.MaxValue //Non-local actors mailbox size is unknown, so consider them lowest priority
    }

    while (take > 0) {
      set = delegates.sortWith((a, b) ⇒ mailboxSize(a) < mailboxSize(b)).take(take) ++ set //Question, doesn't this risk selecting the same actor multiple times?
      take -= set.size
    }

    set
  }
}

/**
 * Returns the set of delegates that occur sequentially 'after' the last delegate from the previous selection
 */
trait RoundRobinSelector {
  private var _last: Int = -1;
  /**
   * @return the number of delegates that will receive each message
   */
  def selectionCount: Int
  /**
   * If there aren't enough delegates to provide the selectionCount, either
   * send the message to fewer, or send the message selectionCount times
   * including more than once to some of the delegates. This setting does
   * not matter if you configure selectionCount to always be less than or
   * equal to the number of delegates in the pool.
   * @return true to send to fewer delegates or false to send to duplicate delegates
   */
  def partialFill: Boolean

  def select(delegates: Seq[ActorRef]): Seq[ActorRef] = {
    val length = delegates.length
    val take = if (partialFill) math.min(selectionCount, length)
    else selectionCount

    val set =
      for (i ← 0 until take) yield {
        _last = (_last + 1) % length
        delegates(_last)
      }

    set
  }
}

/**
 * Capacitors
 *
 * These traits define how to alter the size of the pool according to some desired behavior.
 * Capacitors are required (minimally) by the pool to establish bounds on the number of delegates
 * that may exist in the pool.
 */

/**
 * Ensures a fixed number of delegates in the pool
 */
trait FixedSizeCapacitor {
  /**
   * @return the fixed number of delegates the pool should have
   */
  def limit: Int
  def capacity(delegates: Seq[ActorRef]): Int = (limit - delegates.size) max 0
}

/**
 * Constrains the number of delegates to a bounded range.
 * You probably don't want to use this trait directly,
 * instead look at [[akka.routing.CapacityStrategy]] and [[akka.routing.BoundedCapacityStrategy]].
 * To use this trait you have to implement _eval() which is provided by
 * [[akka.routing.BoundedCapacityStrategy]] in terms of pressure() and filter()
 * methods.
 */
trait BoundedCapacitor {
  /**
   * @return the fewest delegates the pool should ever have
   */
  def lowerBound: Int
  /**
   * @return the most delegates the pool should ever have
   */
  def upperBound: Int

  def capacity(delegates: Seq[ActorRef]): Int = {
    val current = delegates length
    val delta = _eval(delegates)
    val proposed = current + delta

    if (proposed < lowerBound) delta + (lowerBound - proposed)
    else if (proposed > upperBound) delta - (proposed - upperBound)
    else delta
  }

  /**
   * This method is defined when you mix in [[akka.routing.CapacityStrategy]]; it
   * returns the "raw" proposed delta which is then clamped by
   * lowerBound and upperBound.
   * @return proposed delta ignoring bounds
   */
  protected def _eval(delegates: Seq[ActorRef]): Int
}

/**
 * Implements pressure() to return the number of delegates with overly-full mailboxes,
 * where the pressureThreshold method defines what counts as overly-full.
 */
trait MailboxPressureCapacitor {

  /**
   * The pressure will be the number of delegates with at least
   * pressureThreshold messages in their mailbox.
   * @return mailbox size that counts as pressure
   */
  def pressureThreshold: Int
  def pressure(delegates: Seq[ActorRef]): Int =
    delegates count {
      case a: LocalActorRef ⇒ a.underlying.dispatcher.mailboxSize(a.underlying) > pressureThreshold
      case _                ⇒ false
    }
}

/**
 * Implements pressure() to return the number of actors currently processing a
 * message whose reply will be sent to a [[akka.dispatch.Future]].
 * In other words, this capacitor counts how many
 * delegates are tied up actively processing a message, as long as the
 * messages have somebody waiting on the result. "One way" messages with
 * no reply would not be counted.
 */
trait ActiveFuturesPressureCapacitor {
  def pressure(delegates: Seq[ActorRef]): Int =
    delegates count {
      case a: LocalActorRef ⇒ a.underlying.channel.isInstanceOf[Promise[_]]
      case _                ⇒ false
    }
}

/**
 * A [[akka.routing.CapacityStrategy]] implements methods pressure() and filter(), where
 * pressure() returns the number of "busy" delegates, and filter() computes
 * a proposed delta (positive, negative, or zero) in the size of the delegate
 * pool.
 */
trait CapacityStrategy {
  import ActorPool._

  /**
   * This method returns the number of delegates considered busy, or 'pressure level',
   * which will be fed into the capacitor and evaluated against the established threshhold.
   * For instance, in general, if the current pressure level exceeds the capacity of the
   * pool, new delegates will be added.
   * @param delegates the current pool of delegates
   * @return number of busy delegates, between 0 and delegates.length
   */
  def pressure(delegates: Seq[ActorRef]): Int
  /**
   * This method can be used to smooth the response of the capacitor by considering
   * the current pressure and current capacity.
   *
   * @param pressure current number of busy delegates
   * @param capacity current number of delegates
   * @return proposed change in the capacity
   */
  def filter(pressure: Int, capacity: Int): Int

  /**
   * Overrides the _eval() method in [[akka.routing.BoundedCapacity]],
   * using filter and pressure to compute a proposed delta.
   * @param delegates current delegates
   * @return proposed delta in capacity
   */
  protected def _eval(delegates: Seq[ActorRef]): Int = filter(pressure(delegates), delegates.size)
}

/**
 * Use this trait to setup a pool that uses a fixed delegate count.
 */
trait FixedCapacityStrategy extends FixedSizeCapacitor

/**
 * Use this trait to setup a pool that may have a variable number of
 * delegates but always within an established upper and lower limit.
 *
 * If mix this into your pool implementation, you must also provide a
 * PressureCapacitor and a Filter.
 */
trait BoundedCapacityStrategy extends CapacityStrategy with BoundedCapacitor

/**
 * Filters
 *  These traits compute a proposed capacity delta from the pressure (pressure
 *  is the number of busy delegates) and the current capacity.
 */

/**
 * The basic filter trait that composes ramp-up and and back-off subfiltering.
 * filter() is defined to be the sum of rampup() and backoff().
 */
trait Filter {
  /**
   * Computes a proposed positive (or zero) capacity delta.
   * @param pressure the current number of busy delegates
   * @param capacity the current number of total delegates
   * @return proposed increase in capacity
   */
  def rampup(pressure: Int, capacity: Int): Int
  /**
   * Computes a proposed negative (or zero) capacity delta.
   * @param pressure the current number of busy delegates
   * @param capacity the current number of total delegates
   * @return proposed decrease in capacity (as a negative number)
   */
  def backoff(pressure: Int, capacity: Int): Int

  // pass through both filters just to be sure any internal counters
  // are updated consistently. ramping up is always + and backing off
  // is always - and each should return 0 otherwise...
  def filter(pressure: Int, capacity: Int): Int =
    rampup(pressure, capacity) + backoff(pressure, capacity)
}

/**
 * This trait is a convenient shorthand to use the [[akka.routing.BasicRampup]]
 * and [[akka.routing.BasicBackoff]] subfilters together.
 */
trait BasicFilter extends Filter with BasicRampup with BasicBackoff

/**
 * Filter performs steady incremental growth using only the basic ramp-up subfilter.
 * The pool of delegates never gets smaller, only larger.
 */
trait BasicNoBackoffFilter extends BasicRampup {
  def filter(pressure: Int, capacity: Int): Int = rampup(pressure, capacity)
}

/**
 * Basic incremental growth as a percentage of the current pool capacity.
 * Whenever pressure reaches capacity (i.e. all delegates are busy),
 * the capacity is increased by a percentage.
 */
trait BasicRampup {
  /**
   * Percentage to increase capacity whenever all delegates are busy.
   * For example, 0.2 would increase 20%, etc.
   * @return percentage increase in capacity when delegates are all busy.
   */
  def rampupRate: Double

  def rampup(pressure: Int, capacity: Int): Int =
    if (pressure < capacity) 0 else math.ceil(rampupRate * capacity) toInt
}

/**
 * Basic decrement as a percentage of the current pool capacity.
 * Whenever pressure as a percentage of capacity falls below the
 * backoffThreshold, capacity is reduced by the backoffRate.
 */
trait BasicBackoff {
  /**
   * Fraction of capacity the pool has to fall below before backing off.
   * For example, if this is 0.7, then we'll remove some delegates when
   * less than 70% of delegates are busy.
   * @return fraction of busy delegates where we start to backoff
   */
  def backoffThreshold: Double
  /**
   * Fraction of delegates to be removed when the pool reaches the
   * backoffThreshold.
   * @return percentage of delegates to remove
   */
  def backoffRate: Double

  def backoff(pressure: Int, capacity: Int): Int =
    if (capacity > 0 && pressure / capacity < backoffThreshold) math.ceil(-1.0 * backoffRate * capacity) toInt else 0
}
/**
 * This filter tracks the average pressure over the lifetime of the pool (or since last reset) and
 * will begin to reduce capacity once this value drops below the provided threshold.  The number of
 * delegates to cull from the pool is determined by some scaling factor (the backoffRate) multiplied
 * by the difference in capacity and pressure.
 *
 * In essence, [[akka.routing.RunningMeanBackoff]] works the same way as [[akka.routing.BasicBackoff]]
 * except that it uses
 * a running mean pressure and capacity rather than the current pressure and capacity.
 */
trait RunningMeanBackoff {
  /**
   * Fraction of mean capacity the pool has to fall below before backing off.
   * For example, if this is 0.7, then we'll remove some delegates when
   * less than 70% of delegates are busy on average.
   * @return fraction of busy delegates where we start to backoff
   */
  def backoffThreshold: Double
  /**
   * The fraction of delegates to be removed when the running mean reaches the
   * backoffThreshold.
   * @return percentage reduction in capacity
   */
  def backoffRate: Double

  private var _pressure: Double = 0.0
  private var _capacity: Double = 0.0

  def backoff(pressure: Int, capacity: Int): Int = {
    _pressure += pressure
    _capacity += capacity

    if (capacity > 0 && pressure / capacity < backoffThreshold
      && _capacity > 0 && _pressure / _capacity < backoffThreshold) //Why does the entire clause need to be true?
      math.floor(-1.0 * backoffRate * (capacity - pressure)).toInt
    else 0
  }

  /**
   * Resets the running mean pressure and capacity.
   * This is never invoked by the library, you have to do
   * it by hand if there are points in time where it makes
   * sense.
   */
  def backoffReset {
    _pressure = 0.0
    _capacity = 0.0
  }
}
