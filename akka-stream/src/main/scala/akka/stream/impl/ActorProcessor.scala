/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.api.Processor
import org.reactivestreams.spi.Subscriber
import akka.actor._
import akka.stream.MaterializerSettings
import akka.event.LoggingReceive
import java.util.Arrays
import scala.util.control.NonFatal
import org.reactivestreams.api.Consumer
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorConsumer.{ OnNext, OnError, OnComplete, OnSubscribe }
import org.reactivestreams.spi.Subscription
import akka.stream.TimerTransformer

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {

  import Ast._
  def props(settings: MaterializerSettings, op: AstNode): Props =
    (op match {
      case Transform(transformer: TimerTransformer[_, _]) ⇒
        Props(new TimerTransformerProcessorsImpl(settings, transformer))
      case t: Transform      ⇒ Props(new TransformProcessorImpl(settings, t.transformer))
      case s: SplitWhen      ⇒ Props(new SplitWhenProcessorImpl(settings, s.p))
      case g: GroupBy        ⇒ Props(new GroupByProcessorImpl(settings, g.f))
      case m: Merge          ⇒ Props(new MergeImpl(settings, m.other))
      case z: Zip            ⇒ Props(new ZipImpl(settings, z.other))
      case c: Concat         ⇒ Props(new ConcatImpl(settings, c.next))
      case t: Tee            ⇒ Props(new TeeImpl(settings, t.other))
      case cf: Conflate      ⇒ Props(new ConflateImpl(settings, cf.seed, cf.aggregate))
      case ex: Expand        ⇒ Props(new ExpandImpl(settings, ex.seed, ex.extrapolate))
      case bf: Buffer        ⇒ Props(new BufferImpl(settings, bf.size, bf.overflowStrategy))
      case tt: PrefixAndTail ⇒ Props(new PrefixAndTailImpl(settings, tt.n))
      case ConcatAll         ⇒ Props(new ConcatAllImpl(settings))
      case m: MapFuture      ⇒ Props(new MapFutureProcessorImpl(settings, m.f))
    }).withDispatcher(settings.dispatcher)
}

/**
 * INTERNAL API
 */
private[akka] class ActorProcessor[I, O]( final val impl: ActorRef) extends Processor[I, O] with Consumer[I] with ActorProducerLike[O] {
  override val getSubscriber: Subscriber[I] = new ActorSubscriber[I](impl)
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
      upstream.requestMore(requestBatchSize)
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
    upstream.requestMore(inputBuffer.length)
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
    case OnNext(element) ⇒ enqueueInputElement(element)
    case OnComplete      ⇒ onComplete()
    case OnError(cause)  ⇒ onError(cause)
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
private[akka] abstract class FanoutOutputs(val maxBufferSize: Int, val initialBufferSize: Int, self: ActorRef, val pump: Pump)
  extends DefaultOutputTransferStates
  with SubscriberManagement[Any] {

  override type S = ActorSubscription[Any]
  override def createSubscription(subscriber: Subscriber[Any]): S =
    new ActorSubscription(self, subscriber)
  protected var exposedPublisher: ActorPublisher[Any] = _

  private var downstreamBufferSpace = 0
  private var downstreamCompleted = false
  override def demandAvailable = downstreamBufferSpace > 0
  def demandCount: Int = downstreamBufferSpace

  override val subreceive = new SubReceive(waitingExposedPublisher)

  def enqueueOutputElement(elem: Any): Unit = {
    downstreamBufferSpace -= 1
    pushToDownstream(elem)
  }

  def complete(): Unit =
    if (!downstreamCompleted) {
      downstreamCompleted = true
      completeDownstream()
    }

  def cancel(e: Throwable): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      abortDownstream(e)
    }
    if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
  }

  def isClosed: Boolean = downstreamCompleted

  def afterShutdown(): Unit

  override protected def requestFromUpstream(elements: Int): Unit = downstreamBufferSpace += elements

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  override protected def shutdown(completed: Boolean): Unit = {
    if (exposedPublisher ne null) {
      if (completed) exposedPublisher.shutdown(None)
      else exposedPublisher.shutdown(Some(new IllegalStateException("Cannot subscribe to shutdown publisher")))
    }
    afterShutdown()
  }

  override protected def cancelUpstream(): Unit = {
    downstreamCompleted = true
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
      subscribePending()
    case RequestMore(subscription, elements) ⇒
      moreRequested(subscription.asInstanceOf[ActorSubscription[Any]], elements)
      pump.pump()
    case Cancel(subscription) ⇒
      unregisterSubscription(subscription.asInstanceOf[ActorSubscription[Any]])
      pump.pump()
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SoftShutdown
  with Pump {

  // FIXME: make pump a member
  protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, this) {
    override def inputOnError(e: Throwable): Unit = ActorProcessorImpl.this.onError(e)
  }

  protected val primaryOutputs: FanoutOutputs =
    new FanoutOutputs(settings.maxFanOutBufferSize, settings.initialFanOutBufferSize, self, this) {
      override def afterShutdown(): Unit = {
        primaryOutputsShutdown = true
        shutdownHooks()
      }
    }

  override def receive = primaryInputs.subreceive orElse primaryOutputs.subreceive

  protected def onError(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    primaryInputs.cancel()
    primaryOutputs.cancel(e)
    primaryOutputsShutdown = true
    softShutdown()
  }

  override val pumpContext = context

  override def pumpFinished(): Unit = {
    if (primaryInputs.isOpen) primaryInputs.cancel()
    primaryOutputs.complete()
  }
  override def pumpFailed(e: Throwable): Unit = fail(e)

  protected def shutdownHooks(): Unit = {
    primaryInputs.cancel()
    softShutdown()
  }

  var primaryOutputsShutdown = false

  override def postStop(): Unit = {
    // Non-gracefully stopped, do our best here
    if (!primaryOutputsShutdown)
      primaryOutputs.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    throw new IllegalStateException("This actor cannot be restarted")
  }

}
