/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.{ Publisher, Subscriber, Subscription, Processor }
import akka.actor._
import akka.stream.{ ReactiveStreamsConstants, MaterializerSettings, TimerTransformer }
import akka.stream.actor.ActorSubscriber.OnSubscribe
import akka.stream.actor.ActorSubscriberMessage.{ OnNext, OnComplete, OnError }
import java.util.Arrays

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {

  import Ast._
  def props(settings: MaterializerSettings, op: AstNode): Props =
    (op match {
      case fb: FanoutBox     ⇒ Props(new FanoutProcessorImpl(settings, fb.initialBufferSize, fb.maximumBufferSize))
      case t: TimerTransform ⇒ Props(new TimerTransformerProcessorsImpl(settings, t.mkTransformer()))
      case t: Transform      ⇒ Props(new TransformProcessorImpl(settings, t.mkTransformer()))
      case s: SplitWhen      ⇒ Props(new SplitWhenProcessorImpl(settings, s.p))
      case g: GroupBy        ⇒ Props(new GroupByProcessorImpl(settings, g.f))
      case m: Merge          ⇒ Props(new MergeImpl(settings, m.other))
      case z: Zip            ⇒ Props(new ZipImpl(settings, z.other))
      case c: Concat         ⇒ Props(new ConcatImpl(settings, c.next))
      case b: Broadcast      ⇒ Props(new BroadcastImpl(settings, b.other))
      case cf: Conflate      ⇒ Props(new ConflateImpl(settings, cf.seed, cf.aggregate))
      case ex: Expand        ⇒ Props(new ExpandImpl(settings, ex.seed, ex.extrapolate))
      case bf: Buffer        ⇒ Props(new BufferImpl(settings, bf.size, bf.overflowStrategy))
      case tt: PrefixAndTail ⇒ Props(new PrefixAndTailImpl(settings, tt.n))
      case ConcatAll         ⇒ Props(new ConcatAllImpl(settings))
      case m: MapFuture      ⇒ Props(new MapAsyncProcessorImpl(settings, m.f))
    }).withDispatcher(settings.dispatcher)

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}

/**
 * INTERNAL API
 */
private[akka] class ActorProcessor[I, O](impl: ActorRef) extends ActorPublisher[O](impl, None)
  with Processor[I, O] {
  override def onSubscribe(s: Subscription): Unit = impl ! OnSubscribe(s)
  override def onError(t: Throwable): Unit = impl ! OnError(t)
  override def onComplete(): Unit = impl ! OnComplete
  override def onNext(t: I): Unit = impl ! OnNext(t)
}

/**
 * INTERNAL API
 */
private[akka] abstract class BatchingInputBuffer(val size: Int, val pump: Pump) extends DefaultInputTransferStates {
  require(size > 0, "buffer size cannot be zero")
  require((size & (size - 1)) == 0, "buffer size must be a power of two")
  // TODO: buffer and batch sizing heuristics
  private var upstream: Subscription = _
  private val inputBuffer = Array.ofDim[AnyRef](size)
  private var inputBufferElements = 0
  private var nextInputElementCursor = 0
  private var upstreamCompleted = false
  private val IndexMask = size - 1

  private def requestBatchSize = math.max(1, inputBuffer.length / 2)
  private var batchRemaining = requestBatchSize

  override val subreceive: SubReceive = new SubReceive(waitingForUpstream)

  override def dequeueInputElement(): Any = {
    val elem = inputBuffer(nextInputElementCursor)
    inputBuffer(nextInputElementCursor) = null

    batchRemaining -= 1
    if (batchRemaining == 0 && !upstreamCompleted) {
      upstream.request(requestBatchSize)
      batchRemaining = requestBatchSize
    }

    inputBufferElements -= 1
    nextInputElementCursor += 1
    nextInputElementCursor &= IndexMask
    elem
  }

  protected final def enqueueInputElement(elem: Any): Unit = {
    if (isOpen) {
      if (inputBufferElements == size) throw new IllegalStateException("Input buffer overrun")
      inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
      inputBufferElements += 1
    }
    pump.pump()
  }

  override def cancel(): Unit = {
    if (!upstreamCompleted) {
      upstreamCompleted = true
      if (upstream ne null) upstream.cancel()
      clear()
    }
  }
  override def isClosed: Boolean = upstreamCompleted

  private def clear(): Unit = {
    Arrays.fill(inputBuffer, 0, inputBuffer.length, null)
    inputBufferElements = 0
  }

  override def inputsDepleted = upstreamCompleted && inputBufferElements == 0
  override def inputsAvailable = inputBufferElements > 0

  protected def onComplete(): Unit = {
    upstreamCompleted = true
    subreceive.become(completed)
    pump.pump()
  }

  protected def onSubscribe(subscription: Subscription): Unit = {
    assert(subscription != null)
    upstream = subscription
    // Prefetch
    upstream.request(inputBuffer.length)
    subreceive.become(upstreamRunning)
  }

  protected def onError(e: Throwable): Unit = {
    upstreamCompleted = true
    subreceive.become(completed)
    inputOnError(e)
  }

  protected def waitingForUpstream: Actor.Receive = {
    case OnComplete                ⇒ onComplete()
    case OnSubscribe(subscription) ⇒ onSubscribe(subscription)
    case OnError(cause)            ⇒ onError(cause)
  }

  protected def upstreamRunning: Actor.Receive = {
    case OnNext(element)           ⇒ enqueueInputElement(element)
    case OnComplete                ⇒ onComplete()
    case OnError(cause)            ⇒ onError(cause)
    case OnSubscribe(subscription) ⇒ subscription.cancel() // spec rule 2.5
  }

  protected def completed: Actor.Receive = {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
  }

  protected def inputOnError(e: Throwable): Unit = {
    clear()
  }

}

/**
 * INTERNAL API
 */
private[akka] class SimpleOutputs(val actor: ActorRef, val pump: Pump) extends DefaultOutputTransferStates {

  protected var exposedPublisher: ActorPublisher[Any] = _

  protected var subscriber: Subscriber[Any] = _
  protected var downstreamDemand: Long = 0L
  protected var downstreamCompleted = false
  override def demandAvailable = downstreamDemand > 0
  override def demandCount: Long = downstreamDemand

  override def subreceive = _subreceive
  private val _subreceive = new SubReceive(waitingExposedPublisher)

  def enqueueOutputElement(elem: Any): Unit = {
    downstreamDemand -= 1
    subscriber.onNext(elem)
  }

  def complete(): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      if (subscriber ne null) subscriber.onComplete()
      if (exposedPublisher ne null) exposedPublisher.shutdown(None)
    }
  }

  def cancel(e: Throwable): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      if (subscriber ne null) subscriber.onError(e)
      if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
    }
  }

  def isClosed: Boolean = downstreamCompleted

  protected def createSubscription(): Subscription = {
    new ActorSubscription(actor, subscriber)
  }

  private def subscribePending(subscribers: Seq[Subscriber[Any]]): Unit =
    subscribers foreach { sub ⇒
      if (subscriber eq null) {
        subscriber = sub
        subscriber.onSubscribe(createSubscription())
      } else sub.onError(new IllegalStateException(s"${getClass.getSimpleName} ${ReactiveStreamsConstants.SupportsOnlyASingleSubscriber}"))
    }

  protected def waitingExposedPublisher: Actor.Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      subreceive.become(downstreamRunning)
    case other ⇒
      throw new IllegalStateException(s"The first message must be ExposedPublisher but was [$other]")
  }

  protected def downstreamRunning: Actor.Receive = {
    case SubscribePending ⇒
      subscribePending(exposedPublisher.takePendingSubscribers())
    case RequestMore(subscription, elements) ⇒

      // TODO centralize overflow protection
      downstreamDemand += elements
      if (downstreamDemand < 0) {
        // Long has overflown
        val demandOverflowException = new IllegalStateException(ReactiveStreamsConstants.TotalPendingDemandMustNotExceedLongMaxValue)
        cancel(demandOverflowException)
      }

      pump.pump()
    case Cancel(subscription) ⇒
      downstreamCompleted = true
      exposedPublisher.shutdown(Some(new ActorPublisher.NormalShutdownException))
      pump.pump()
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with Pump
  with Stash {

  // FIXME: make pump a member
  protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, this) {
    override def inputOnError(e: Throwable): Unit = ActorProcessorImpl.this.onError(e)
  }

  protected val primaryOutputs: Outputs = new SimpleOutputs(self, this)

  /**
   * Subclass may override [[#activeReceive]]
   */
  final override def receive = {
    // FIXME using Stash mailbox is not the best for performance, we probably want a better solution to this
    case ep: ExposedPublisher ⇒
      primaryOutputs.subreceive(ep)
      context become activeReceive
      unstashAll()
    case _ ⇒ stash()
  }

  def activeReceive: Receive = primaryInputs.subreceive orElse primaryOutputs.subreceive

  protected def onError(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    primaryInputs.cancel()
    primaryOutputs.cancel(e)
    context.stop(self)
  }

  override def pumpFinished(): Unit = {
    primaryInputs.cancel()
    primaryOutputs.complete()
    context.stop(self)
  }

  override def pumpFailed(e: Throwable): Unit = fail(e)

  override def postStop(): Unit = {
    primaryInputs.cancel()
    primaryOutputs.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    throw new IllegalStateException("This actor cannot be restarted", reason)
  }

}
