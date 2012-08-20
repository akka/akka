package akka.pattern.throttle

import Throttler._
import akka.actor.{ ActorRef, Actor, LoggingFSM }
import scala.concurrent.util.Duration
import scala.util.control.NonFatal
import scala.collection.immutable.{ Queue ⇒ Q }

object TimerBasedThrottler {
  private[throttle] case object Tick

  // States of the FSM
  private[throttle] sealed trait State
  // Idle means we don't deliver messages, either because there are none, or because no target was set.
  private[throttle] case object Idle extends State
  // Active means we the target is set and we have a message queue that is non-empty.
  private[throttle] case object Active extends State

  // Messages, as we queue them to be sent later
  private[throttle] case class Message(message: Any, sender: ActorRef)

  // The data of the FSM
  private[throttle] sealed case class Data(target: Option[ActorRef],
                                           callsLeftInThisPeriod: Int,
                                           queue: Q[Message])
}

import TimerBasedThrottler._

/**
 * A [[akka.pattern.throttle.Throttler]] that uses a timer to control the message delivery rate.
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
 *   throttler ! Queue("1")
 *   throttler ! Queue("2")
 *   throttler ! Queue("3")
 *   // These two will wait at least until 1 second has passed
 *   throttler ! Queue("4")
 *   throttler ! Queue("5")
 * }}}
 *
 * ==Implementation notes==
 * This throttler implementation installs a timer that repeats every `rate.durationInMillis` and enables `rate.numberOfCalls`
 * additional calls to take place. A [[akka.pattern.throttle.TimerBasedThrottler]] uses very few system resources, provided the rate's duration is not too
 * fine-grained (which would cause a lot of timer invocations); for example, it does not store the calling history
 * as other throttlers may need to do.
 *
 * However, a [[akka.pattern.throttle.TimerBasedThrottler]] only provides ''weak guarantees'' on the rate:
 *
 *  - Only ''delivery'' times are taken into account: if, for example, the throttler is used to throttle
 *    requests to an external web service then only the start times of the web requests are considered.
 *    If a web request takes very long on the server then more than `rate.numberOfCalls`-many requests
 *    may be observed on the server in an interval of duration `rate.durationInMillis()`.
 *  - There may be intervals of duration `rate.durationInMillis()` that contain more than `rate.numberOfCalls`
 *    message deliveries: a [[akka.pattern.throttle.TimerBasedThrottler]] only makes guarantees for the intervals
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
 * @see [[akka.pattern.throttle.Throttler]]
 */
class TimerBasedThrottler(var rate: Rate) extends Actor with Throttler with LoggingFSM[State, Data] {

  startWith(Idle, Data(None, rate.numberOfCalls, Q[Message]()))

  // Idle: no messages, or target not set
  when(Idle) {
    // Set the rate
    case Event(SetRate(rate), d) ⇒ {
      this.rate = rate
      stay using d.copy(callsLeftInThisPeriod = rate.numberOfCalls)
    }

    // Set the target
    case Event(SetTarget(t @ Some(_)), d) if !d.queue.isEmpty ⇒
      goto(Active) using deliverMessages(d.copy(target = t))
    case Event(SetTarget(t), d) ⇒
      stay using d.copy(target = t)

    // Queuing
    case Event(Queue(msg), d @ Data(None, _, queue)) ⇒
      stay using d.copy(queue = queue.enqueue(Message(msg, context.sender)))
    case Event(Queue(msg), d @ Data(Some(_), _, Seq())) ⇒
      goto(Active) using deliverMessages(d.copy(queue = Q(Message(msg, context.sender))))
    // Note: The case Event(Queue(msg), t @ Data(Some(_), _, _, Seq(_*))) should never happen here.
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
      goto(Idle) using d.copy(target = None)

    // Set the target (when the new target is not None)
    case Event(SetTarget(t @ Some(_)), d) ⇒
      stay using d.copy(target = t)

    // Queue a message (when we cannot send messages in the current period anymore)
    case Event(Queue(msg), d @ Data(_, 0, queue)) ⇒
      stay using d.copy(queue = queue.enqueue(Message(msg, context.sender)))

    // Queue a message (when we can send some more messages in the current period)
    case Event(Queue(msg), d @ Data(_, _, queue)) ⇒
      stay using deliverMessages(d.copy(queue = queue.enqueue(Message(msg, context.sender))))

    // Period ends and we have no more messages
    case Event(Tick, d @ Data(_, _, Seq())) ⇒
      goto(Idle)

    // Period ends and we get more occasions to send messages
    case Event(Tick, d @ Data(_, _, _)) ⇒
      stay using deliverMessages(d.copy(callsLeftInThisPeriod = rate.numberOfCalls))
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

    queue.take(nrOfMsgToSend).foreach((x: Message) ⇒ {
      /*
       *  In case message deliver fails, we throw our own exception so that the supervisor
       *  can deal with it appropriately.
       */
      try {
        data.target.get.tell(x.message, x.sender)
      } catch {
        case NonFatal(ex) ⇒ throw new FailedToSendException("tell() failed.", ex)
      }
    })

    data.copy(queue = queue.drop(nrOfMsgToSend), callsLeftInThisPeriod = data.callsLeftInThisPeriod - nrOfMsgToSend)
  }
}