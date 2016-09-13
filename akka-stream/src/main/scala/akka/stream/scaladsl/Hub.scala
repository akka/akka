/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.atomic.{ AtomicLong, AtomicReference }

import akka.NotUsed
import akka.dispatch.AbstractNodeQueue
import akka.stream._
import akka.stream.stage._

import scala.annotation.tailrec
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }

/**
 * A MergeHub is a special streaming hub that is able to collect streamed elements from a dynamic set of
 * producers. It consists of two parts, a [[Source]] and a [[Sink]]. The [[Source]] streams the element to a consumer from
 * its merged inputs. Once the consumer has been materialized, the [[Source]] returns a materialized value which is
 * the corresponding [[Sink]]. This [[Sink]] can then be materialized arbitrary many times, where each of the new
 * materializations will feed its consumed elements to the original [[Source]].
 */
object MergeHub {
  private val Cancel = -1

  /**
   * Creates a [[Source]] that emits elements merged from a dynamic set of producers. After the [[Source]] returned
   * by this method is materialized, it returns a [[Sink]] as a materialized value. This [[Sink]] can be materialized
   * arbitrary many times and each of the materializations will feed the elements into the original [[Source]].
   *
   * Every new materialization of the [[Source]] results in a new, independent hub, which materializes to its own
   * [[Sink]] for feeding that materialization.
   *
   * If one of the inputs fails the [[Sink]], the [[Source]] is failed in turn (possibly jumping over already buffered
   * elements). Completed [[Sink]]s are simply removed. Once the [[Source]] is cancelled, the Hub is considered closed
   * and any new producers using the [[Sink]] will be cancelled.
   *
   * @param perProducerBufferSize Buffer space used per producer. Default value is 16.
   */
  def source[T](perProducerBufferSize: Int): Source[T, Sink[T, NotUsed]] =
    Source.fromGraph(new MergeHub[T](perProducerBufferSize))

  /**
   * Creates a [[Source]] that emits elements merged from a dynamic set of producers. After the [[Source]] returned
   * by this method is materialized, it returns a [[Sink]] as a materialized value. This [[Sink]] can be materialized
   * arbitrary many times and each of the materializations will feed the elements into the original [[Source]].
   *
   * Every new materialization of the [[Source]] results in a new, independent hub, which materializes to its own
   * [[Sink]] for feeding that materialization.
   *
   * If one of the inputs fails the [[Sink]], the [[Source]] is failed in turn (possibly jumping over already buffered
   * elements). Completed [[Sink]]s are simply removed. Once the [[Source]] is cancelled, the Hub is considered closed
   * and any new producers using the [[Sink]] will be cancelled.
   */
  def source[T]: Source[T, Sink[T, NotUsed]] = source(perProducerBufferSize = 16)

  final class ProducerFailed(msg: String, cause: Throwable) extends RuntimeException(msg, cause)
}

/**
 * INTERNAL API
 */
private[akka] class MergeHub[T](perProducerBufferSize: Int) extends GraphStageWithMaterializedValue[SourceShape[T], Sink[T, NotUsed]] {
  require(perProducerBufferSize > 0, "Buffer size must be positive")

  val out: Outlet[T] = Outlet("MergeHub.out")
  override val shape: SourceShape[T] = SourceShape(out)

  // Half of buffer size, rounded up
  private[this] val DemandThreshold = (perProducerBufferSize / 2) + (perProducerBufferSize % 2)

  private sealed trait Event {
    def id: Long
  }

  private final case class Element(id: Long, elem: T) extends Event
  private final case class Register(id: Long, demandCallback: AsyncCallback[Long]) extends Event
  private final case class Deregister(id: Long) extends Event

  final class InputState(signalDemand: AsyncCallback[Long]) {
    private var untilNextDemandSignal = DemandThreshold

    def onElement(): Unit = {
      untilNextDemandSignal -= 1
      if (untilNextDemandSignal == 0) {
        untilNextDemandSignal = DemandThreshold
        signalDemand.invoke(DemandThreshold)
      }
    }

    def close(): Unit = signalDemand.invoke(MergeHub.Cancel)

  }

  final class MergedSourceLogic(_shape: Shape, producerCount: AtomicLong) extends GraphStageLogic(_shape) with OutHandler {
    /*
     * Basically all merged messages are shared in this queue. Individual buffer sizes are enforced by tracking
     * demand per producer in the 'demands' Map. One twist here is that the same queue contains control messages,
     * too. Since the queue is read only if the output port has been pulled, downstream backpressure can delay
     * processing of control messages. This causes no issues though, see the explanation in 'tryProcessNext'.
     */
    private val queue = new AbstractNodeQueue[Event] {}
    @volatile private[this] var needWakeup = false
    @volatile private[this] var shuttingDown = false

    private[this] val demands = scala.collection.mutable.LongMap.empty[InputState]
    private[this] val wakeupCallback = getAsyncCallback[NotUsed]((_) ⇒
      // We are only allowed to dequeue if we are not backpressured. See comment in tryProcessNext() for details.
      if (isAvailable(out)) tryProcessNext(firstAttempt = true)
    )

    setHandler(out, this)

    // Returns true when we have not consumed demand, false otherwise
    private def onEvent(ev: Event): Boolean = ev match {
      case Element(id, elem) ⇒
        demands(id).onElement()
        push(out, elem)
        false
      case Register(id, callback) ⇒
        demands.put(id, new InputState(callback))
        true
      case Deregister(id) ⇒
        demands.remove(id)
        true
    }

    override def onPull(): Unit = tryProcessNext(firstAttempt = true)

    @tailrec private def tryProcessNext(firstAttempt: Boolean): Unit = {
      val nextElem = queue.poll()

      // That we dequeue elements from the queue when there is demand means that Register and Deregister messages
      // might be delayed for arbitrary long. This is not a problem as Register is only interesting if it is followed
      // by actual elements, which would be delayed anyway by the backpressure.
      // Unregister is only used to keep the map growing too large, but otherwise it is not critical to process it
      // timely. In fact, the only way the map could keep growing would mean that we dequeue Registers from the
      // queue, but then we will eventually reach the Deregister message, too.
      if (nextElem ne null) {
        needWakeup = false
        if (onEvent(nextElem)) tryProcessNext(firstAttempt = true)
      } else {
        needWakeup = true
        // additional poll() to grab any elements that might missed the needWakeup
        // and have been enqueued just after it
        if (firstAttempt)
          tryProcessNext(firstAttempt = false)
      }
    }

    def isShuttingDown: Boolean = shuttingDown

    // External API
    def enqueue(ev: Event): Unit = {
      queue.add(ev)
      /*
       * Simple volatile var is enough, there is no need for a CAS here. The first important thing to note
       * that we don't care about double-wakeups. Since the "wakeup" is actually handled by an actor message
       * (AsyncCallback) we don't need to handle this case, a double-wakeup will be idempotent (only wasting some cycles).
       *
       * The only case that we care about is a missed wakeup. The characteristics of a missed wakeup are the following:
       *  (1) there is at least one message in the queue
       *  (2) the consumer is not running right now
       *  (3) no wakeupCallbacks are pending
       *  (4) all producers exited this method
       *
       * From the above we can deduce that
       *  (5) needWakeup = true at some point in time. This is implied by (1) and (2) and the
       *      'tryProcessNext' method
       *  (6) There must have been one producer that observed needWakeup = false. This follows from (4) and (3)
       *      and the implementation of this method. In addition, this producer arrived after needWakeup = true,
       *      since before that, every queued elements have been consumed.
       *  (7) There have been at least one producer that observed needWakeup = true and enqueued an element and
       *      a wakeup signal. This follows from (5) and (6), and the fact that either this method sets
       *      needWakeup = false, or the 'tryProcessNext' method, i.e. a wakeup must happened since (5)
       *  (8) If there were multiple producers satisfying (6) take the last one. Due to (6), (3) and (4) we know
       *      there cannot be a wakeup pending, and we just enqueued an element, so (1) holds. Since we are the last
       *      one, (2) must be true or there is no lost wakeup. However, due to (7) we know there was at least one
       *      wakeup (otherwise needWakeup = true). Now, if the consumer is still running (2) is violated,
       *      if not running then needWakeup = false is violated (which comes from (6)). No matter what,
       *      contradiction. QED.
       *
       */
      if (needWakeup) {
        needWakeup = false
        wakeupCallback.invoke(NotUsed)
      }
    }

    override def postStop(): Unit = {
      // First announce that we are shutting down. This will notify late-comers to not even put anything in the queue
      shuttingDown = true
      // Anybody that missed the announcement needs to be notified.
      var event = queue.poll()
      while (event ne null) {
        event match {
          case Register(_, demandCallback) ⇒ demandCallback.invoke(MergeHub.Cancel)
          case _                           ⇒
        }
        event = queue.poll()
      }

      // Kill everyone else
      val states = demands.valuesIterator
      while (states.hasNext) {
        states.next().close()
      }
    }
  }

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Sink[T, NotUsed]) = {
    val idCounter = new AtomicLong()

    val logic: MergedSourceLogic = new MergedSourceLogic(shape, idCounter)

    val sink = new GraphStage[SinkShape[T]] {
      val in: Inlet[T] = Inlet("MergeHub.in")
      override val shape: SinkShape[T] = SinkShape(in)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler {
        // Start from non-zero demand to avoid initial delays.
        // The HUB will expect this behavior.
        private[this] var demand: Long = perProducerBufferSize
        private[this] val id = idCounter.getAndIncrement()

        override def preStart(): Unit = {
          if (!logic.isShuttingDown) {
            logic.enqueue(Register(id, getAsyncCallback(onDemand)))

            // At this point, we could be in the unfortunate situation that:
            // - we missed the shutdown announcement and entered this arm of the if statement
            // - *before* we enqueued our Register event, the Hub already finished looking at the queue
            //   and is now dead, so we are never notified again.
            // To safeguard against this, we MUST check the announcement again. This is enough:
            // if the Hub is no longer looking at the queue, then it must be that isShuttingDown must be already true.
            if (!logic.isShuttingDown) pullWithDemand()
            else completeStage()
          } else {
            completeStage()
          }
        }
        override def postStop(): Unit = {
          // Unlike in the case of preStart, we don't care about the Hub no longer looking at the queue.
          if (!logic.isShuttingDown) logic.enqueue(Deregister(id))
        }

        override def onPush(): Unit = {
          logic.enqueue(Element(id, grab(in)))
          if (demand > 0) pullWithDemand()
        }

        private def pullWithDemand(): Unit = {
          demand -= 1
          pull(in)
        }

        // Make some noise
        override def onUpstreamFailure(ex: Throwable): Unit = {
          throw new MergeHub.ProducerFailed("Upstream producer failed with exception, " +
            "removing from MergeHub now", ex)
        }

        private def onDemand(moreDemand: Long): Unit = {
          if (moreDemand == MergeHub.Cancel) completeStage()
          else {
            demand += moreDemand
            if (!hasBeenPulled(in)) pullWithDemand()
          }
        }

        setHandler(in, this)
      }

    }

    (logic, Sink.fromGraph(sink))
  }
}

/**
 * A BroadcastHub is a special streaming hub that is able to broadcast streamed elements to a dynamic set of consumers.
 * It consissts of two parts, a [[Sink]] and a [[Source]]. The [[Sink]] broadcasts elements from a producer to the
 * actually live consumers it has. Once the producer has been materialized, the [[Sink]] it feeds into returns a
 * materialized value which is the corresponding [[Source]]. This [[Source]] can be materialized arbitrary many times,
 * where weach of the new materializations will receive their elements from the original [[Sink]].
 */
object BroadcastHub {

  /**
   * Creates a [[Sink]] that receives elements from its upstream producer and broadcasts them to a dynamic set
   * of consumers. After the [[Sink]] returned by this method is materialized, it returns a [[Source]] as materialized
   * value. This [[Source]] can be materialized arbitrary many times and each materialization will receive the
   * broadcast elements form the ofiginal [[Sink]].
   *
   * Every new materialization of the [[Sink]] results in a new, independent hub, which materializes to its own
   * [[Source]] for consuming the [[Sink]] of that materialization.
   *
   * If the original [[Sink]] is failed, then the failure is immediately propagated to all of its materialized
   * [[Source]]s (possibly jumping over already buffered elements). If the original [[Sink]] is completed, then
   * all corresponding [[Source]]s are completed. Both failure and normal completion is "remembered" and later
   * materializations of the [[Source]] will see the same (failure or completion) state. [[Source]]s that are
   * cancelled are simply removed from the dynamic set of consumers.
   *
   * @param bufferSize Buffer size used by the producer. Gives an upper bound on how "far" from each other two
   *                   concurrent consumers can be in terms of element. If this buffer is full, the producer
   *                   is backpressured. Must be a power of two and less than 4096.
   */
  def sink[T](bufferSize: Int): Sink[T, Source[T, NotUsed]] = Sink.fromGraph(new BroadcastHub[T](bufferSize))

  /**
   * Creates a [[Sink]] that receives elements from its upstream producer and broadcasts them to a dynamic set
   * of consumers. After the [[Sink]] returned by this method is materialized, it returns a [[Source]] as materialized
   * value. This [[Source]] can be materialized arbitrary many times and each materialization will receive the
   * broadcast elements form the ofiginal [[Sink]].
   *
   * Every new materialization of the [[Sink]] results in a new, independent hub, which materializes to its own
   * [[Source]] for consuming the [[Sink]] of that materialization.
   *
   * If the original [[Sink]] is failed, then the failure is immediately propagated to all of its materialized
   * [[Source]]s (possibly jumping over already buffered elements). If the original [[Sink]] is completed, then
   * all corresponding [[Source]]s are completed. Both failure and normal completion is "remembered" and later
   * materializations of the [[Source]] will see the same (failure or completion) state. [[Source]]s that are
   * cancelled are simply removed from the dynamic set of consumers.
   *
   */
  def sink[T]: Sink[T, Source[T, NotUsed]] = sink(bufferSize = 256)

}

/**
 * INTERNAL API
 */
private[akka] class BroadcastHub[T](bufferSize: Int) extends GraphStageWithMaterializedValue[SinkShape[T], Source[T, NotUsed]] {
  require(bufferSize > 0, "Buffer size must be positive")
  require(bufferSize < 4096, "Buffer size larger then 4095 is not allowed")
  require((bufferSize & bufferSize - 1) == 0, "Buffer size must be a power of two")

  private val Mask = bufferSize - 1
  private val WheelMask = (bufferSize * 2) - 1

  val in: Inlet[T] = Inlet("BroadcastHub.in")
  override val shape: SinkShape[T] = SinkShape(in)

  // Half of buffer size, rounded up
  private[this] val DemandThreshold = (bufferSize / 2) + (bufferSize % 2)

  private sealed trait HubEvent

  private object RegistrationPending extends HubEvent
  private final case class UnRegister(id: Long, previousOffset: Int, finalOffset: Int) extends HubEvent
  private final case class Advance(id: Long, previousOffset: Int) extends HubEvent
  private final case class NeedWakeup(id: Long, previousOffset: Int, currentOffset: Int) extends HubEvent

  private final case class Consumer(id: Long, callback: AsyncCallback[ConsumerEvent])

  private object Completed

  private sealed trait HubState
  private case class Open(callbackFuture: Future[AsyncCallback[HubEvent]], registrations: List[Consumer]) extends HubState
  private case class Closed(failure: Option[Throwable]) extends HubState

  private class BroadcastSinkLogic(_shape: Shape)
    extends GraphStageLogic(_shape) with InHandler {

    private[this] val callbackPromise: Promise[AsyncCallback[HubEvent]] = Promise()
    private[this] val noRegistrationsState = Open(callbackPromise.future, Nil)
    val state = new AtomicReference[HubState](noRegistrationsState)

    // Start from values that will almost immediately overflow. This has no effect on performance, any starting
    // number will do, however, this protects from regressions as these values *almost surely* overflow and fail
    // tests if someone makes a mistake.
    @volatile private[this] var tail = Int.MaxValue
    private[this] var head = Int.MaxValue
    /*
     * An Array with a published tail ("latest message") and a privately maintained head ("earliest buffered message").
     * Elements are published by simply putting them into the array and bumping the tail. If necessary, certain
     * consumers are sent a wakeup message through an AsyncCallback.
     */
    private[this] val queue = Array.ofDim[AnyRef](bufferSize)
    /* This is basically a classic Bucket Queue: https://en.wikipedia.org/wiki/Bucket_queue
     * (in fact, this is the variant described in the Optimizations section, where the given set
     * of priorities always fall to a range
     *
     * This wheel tracks the position of Consumers relative to the slowest ones. Every slot
     * contains a list of Consumers being known at that location (this might be out of date!).
     * Consumers from time to time send Advance messages to indicate that they have progressed
     * by reading from the broadcast queue. Consumers that are blocked (due to reaching tail) request
     * a wakeup and update their position at the same time.
     *
     */
    private[this] val consumerWheel = Array.fill[List[Consumer]](bufferSize * 2)(Nil)
    private[this] var activeConsumers = 0

    override def preStart(): Unit = {
      setKeepGoing(true)
      callbackPromise.success(getAsyncCallback[HubEvent](onEvent))
      pull(in)
    }

    // Cannot complete immediately if there is no space in the queue to put the completion marker
    override def onUpstreamFinish(): Unit = if (!isFull) complete()

    override def onPush(): Unit = {
      publish(grab(in))
      if (!isFull) pull(in)
    }

    private def onEvent(ev: HubEvent): Unit = {
      ev match {
        case RegistrationPending ⇒
          state.getAndSet(noRegistrationsState).asInstanceOf[Open].registrations foreach { consumer ⇒
            val startFrom = head
            activeConsumers += 1
            addConsumer(consumer, startFrom)
            consumer.callback.invoke(Initialize(startFrom))
          }

        case UnRegister(id, previousOffset, finalOffset) ⇒
          activeConsumers -= 1
          val consumer = findAndRemoveConsumer(id, previousOffset)
          if (activeConsumers == 0) {
            if (isClosed(in)) completeStage()
            else if (head != finalOffset) {
              // If our final consumer goes away, we roll forward the buffer so a subsequent consumer does not
              // see the already consumed elements. This feature is quite handy.
              while (head != finalOffset) {
                queue(head & Mask) = null
                head += 1
              }
              head = finalOffset
              if (!hasBeenPulled(in)) pull(in)
            }
          } else checkUnblock(previousOffset)
        case Advance(id, previousOffset) ⇒
          val newOffset = previousOffset + DemandThreshold
          // Move the consumer from its last known offest to its new one. Check if we are unblocked.
          val consumer = findAndRemoveConsumer(id, previousOffset)
          addConsumer(consumer, newOffset)
          checkUnblock(previousOffset)
        case NeedWakeup(id, previousOffset, currentOffset) ⇒
          // Move the consumer from its last known offest to its new one. Check if we are unblocked.
          val consumer = findAndRemoveConsumer(id, previousOffset)
          addConsumer(consumer, currentOffset)

          // Also check if the consumer is now unblocked since we published an element since it went asleep.
          if (currentOffset != tail) consumer.callback.invoke(Wakeup)
          checkUnblock(previousOffset)
      }
    }

    // Producer API
    // We are full if the distance between the slowest (known) consumer and the fastest (known) consumer is
    // the buffer size. We must wait until the slowest either advances, or cancels.
    private def isFull: Boolean = tail - head == bufferSize

    override def onUpstreamFailure(ex: Throwable): Unit = {
      val failMessage = HubCompleted(Some(ex))

      // Notify pending consumers and set tombstone
      state.getAndSet(Closed(Some(ex))).asInstanceOf[Open].registrations foreach { consumer ⇒
        consumer.callback.invoke(failMessage)
      }

      // Notify registered consumers
      consumerWheel.iterator.flatMap(_.iterator) foreach { consumer ⇒
        consumer.callback.invoke(failMessage)
      }
      failStage(ex)
    }

    /*
     * This method removes a consumer with a given ID from the known offset and returns it.
     *
     * NB: You cannot remove a consumer without knowing its last offset! Consumers on the Source side always must
     * track this so this can be a fast operation.
     */
    private def findAndRemoveConsumer(id: Long, offset: Int): Consumer = {
      // TODO: Try to eliminate modulo division somehow...
      val wheelSlot = offset & WheelMask
      var consumersInSlot = consumerWheel(wheelSlot)
      //debug(s"consumers before removal $consumersInSlot")
      var remainingConsumersInSlot: List[Consumer] = Nil
      var removedConsumer: Consumer = null

      while (consumersInSlot.nonEmpty) {
        val consumer = consumersInSlot.head
        if (consumer.id != id) remainingConsumersInSlot = consumer :: remainingConsumersInSlot
        else removedConsumer = consumer
        consumersInSlot = consumersInSlot.tail
      }
      consumerWheel(wheelSlot) = remainingConsumersInSlot
      removedConsumer
    }

    /*
     * After removing a Consumer from a wheel slot (because it cancelled, or we moved it because it advanced)
     * we need to check if it was blocking us from advancing (being the slowest).
     */
    private def checkUnblock(offsetOfConsumerRemoved: Int): Unit = {
      if (unblockIfPossible(offsetOfConsumerRemoved)) {
        if (isClosed(in)) complete()
        else if (!hasBeenPulled(in)) pull(in)
      }
    }

    private def unblockIfPossible(offsetOfConsumerRemoved: Int): Boolean = {
      var unblocked = false
      if (offsetOfConsumerRemoved == head) {
        // Try to advance along the wheel. We can skip any wheel slots which have no waiting Consumers, until
        // we either find a nonempty one, or we reached the end of the buffer.
        while (consumerWheel(head & WheelMask).isEmpty && head != tail) {
          queue(head & Mask) = null
          head += 1
          unblocked = true
        }
      }
      unblocked
    }

    private def addConsumer(consumer: Consumer, offset: Int): Unit = {
      val slot = offset & WheelMask
      consumerWheel(slot) = consumer :: consumerWheel(slot)
    }

    /*
     * Send a wakeup signal to all the Consumers at a certain wheel index. Note, this needs the actual index,
     * which is offset modulo (bufferSize + 1).
     */
    private def wakeupIdx(idx: Int): Unit = {
      val itr = consumerWheel(idx).iterator
      while (itr.hasNext) itr.next().callback.invoke(Wakeup)
    }

    private def complete(): Unit = {
      val idx = tail & Mask
      val wheelSlot = tail & WheelMask
      queue(idx) = Completed
      wakeupIdx(wheelSlot)
      tail = tail + 1
      if (activeConsumers == 0) {
        val completedMessage = HubCompleted(None)
        // Notify pending consumers and set tombstone
        state.getAndSet(Closed(None)).asInstanceOf[Open].registrations foreach { consumer ⇒
          consumer.callback.invoke(completedMessage)
        }

        // Existing consumers have already consumed all elements and will see completion status in the queue
        completeStage()
      }
    }

    private def publish(elem: T): Unit = {
      val idx = tail & Mask
      val wheelSlot = tail & WheelMask
      queue(idx) = elem.asInstanceOf[AnyRef]
      // Publish the new tail before calling the wakeup
      tail = tail + 1
      wakeupIdx(wheelSlot)
    }

    // Consumer API
    def poll(offset: Int): AnyRef = {
      if (offset == tail) null
      else queue(offset & Mask)
    }

    setHandler(in, this)

  }

  private sealed trait ConsumerEvent
  private object Wakeup extends ConsumerEvent
  private final case class HubCompleted(failure: Option[Throwable]) extends ConsumerEvent
  private final case class Initialize(offset: Int) extends ConsumerEvent

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Source[T, NotUsed]) = {
    val idCounter = new AtomicLong()

    val logic = new BroadcastSinkLogic(shape)

    val source = new GraphStage[SourceShape[T]] {
      val out: Outlet[T] = Outlet("BroadcastHub.out")
      override val shape: SourceShape[T] = SourceShape(out)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with OutHandler {
        private[this] var untilNextAdvanceSignal = DemandThreshold
        private[this] val id = idCounter.getAndIncrement()
        private[this] var offsetInitialized = false
        private[this] var hubCallback: AsyncCallback[HubEvent] = _

        /*
         * We need to track our last offset that we published to the Hub. The reason is, that for efficiency reasons,
         * the Hub can only look up and move/remove Consumers with known wheel slots. This means that no extra hash-map
         * is needed, but it also means that we need to keep track of both our current offset, and the last one that
         * we published.
         */
        private[this] var previousPublishedOffset = 0
        private[this] var offset = 0

        override def preStart(): Unit = {
          val callback = getAsyncCallback(onCommand)

          val onHubReady: Try[AsyncCallback[HubEvent]] ⇒ Unit = {
            case Success(callback) ⇒
              hubCallback = callback
              if (isAvailable(out) && offsetInitialized) onPull()
              callback.invoke(RegistrationPending)
            case Failure(ex) ⇒
              failStage(ex)
          }

          @tailrec def register(): Unit = {
            logic.state.get() match {
              case Closed(Some(ex)) ⇒ failStage(ex)
              case Closed(None)     ⇒ completeStage()
              case previousState @ Open(callbackFuture, registrations) ⇒
                val newRegistrations = Consumer(id, callback) :: registrations
                if (logic.state.compareAndSet(previousState, Open(callbackFuture, newRegistrations))) {
                  callbackFuture.onComplete(getAsyncCallback(onHubReady).invoke)(materializer.executionContext)
                } else register()
            }
          }

          /*
           * Note that there is a potential race here. First we add ourselves to the pending registrations, then
           * we send RegistrationPending. However, another downstream might have triggered our registration by its
           * own RegistrationPending message, since we are in the list already.
           * This means we might receive an onCommand(Initialize(offset)) *before* onHubReady fires so it is important
           * to only serve elements after both offsetInitialized = true and hubCallback is not null.
           */
          register()

        }

        override def onPull(): Unit = {
          if (offsetInitialized && (hubCallback ne null)) {
            val elem = logic.poll(offset)

            elem match {
              case null ⇒
                hubCallback.invoke(NeedWakeup(id, previousPublishedOffset, offset))
                previousPublishedOffset = offset
                untilNextAdvanceSignal = DemandThreshold
              case Completed ⇒
                completeStage()
              case _ ⇒
                push(out, elem.asInstanceOf[T])
                offset += 1
                untilNextAdvanceSignal -= 1
                if (untilNextAdvanceSignal == 0) {
                  untilNextAdvanceSignal = DemandThreshold
                  val previousOffset = previousPublishedOffset
                  previousPublishedOffset += DemandThreshold
                  hubCallback.invoke(Advance(id, previousOffset))
                }
            }
          }
        }

        override def postStop(): Unit = {
          if (hubCallback ne null)
            hubCallback.invoke(UnRegister(id, previousPublishedOffset, offset))
        }

        private def onCommand(cmd: ConsumerEvent): Unit = cmd match {
          case HubCompleted(Some(ex)) ⇒ failStage(ex)
          case HubCompleted(None)     ⇒ completeStage()
          case Wakeup ⇒
            if (isAvailable(out)) onPull()
          case Initialize(initialOffset) ⇒
            offsetInitialized = true
            previousPublishedOffset = initialOffset
            offset = initialOffset
            if (isAvailable(out) && (hubCallback ne null)) onPull()
        }

        setHandler(out, this)
      }
    }

    (logic, Source.fromGraph(source))
  }
}