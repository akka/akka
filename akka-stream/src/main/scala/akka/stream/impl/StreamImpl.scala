/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.api.{ Producer, Consumer, Processor }
import akka.actor.{ Actor, ActorRef, ActorRefFactory, Props, PoisonPill }
import org.reactivestreams.spi.{ Publisher, Subscription, Subscriber }
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.stream.{ Stream, GeneratorSettings, ProcessorGenerator }
import scala.collection.immutable

/**
 * INTERNAL API
 */
private[akka] object Ast {
  trait AstNode

  case class Transform(zero: Any, f: (Any, Any) ⇒ (Any, immutable.Seq[Any]), onComplete: Any ⇒ immutable.Seq[Any]) extends AstNode
}

/**
 * INTERNAL API
 */
private[akka] case class StreamImpl[I, O](producer: Producer[I], ops: List[Ast.AstNode]) extends Stream[O] {
  import Ast._
  // Storing ops in reverse order
  private def andThen[U](op: AstNode): Stream[U] = this.copy(ops = op :: ops)

  def map[U](f: O ⇒ U): Stream[U] = transform(())((_, in) ⇒ ((), List(f(in))))

  def filter(p: O ⇒ Boolean): Stream[O] = transform(())((_, in) ⇒ if (p(in)) ((), List(in)) else ((), Nil))

  def grouped(n: Int): Stream[immutable.Seq[O]] =
    transform[immutable.Seq[O], immutable.Seq[O]](Nil, (x: immutable.Seq[O]) ⇒ List(x)) { (buf: immutable.Seq[O], in: O) ⇒
      val group = buf :+ in
      if (group.size == n) (Nil, List(group))
      else (group, Nil)
    }

  def mapConcat[U](f: O ⇒ immutable.Seq[U]): Stream[U] = transform(())((_, in) ⇒ ((), f(in)))

  def transform[S, U](zero: S)(f: (S, O) ⇒ (S, immutable.Seq[U])): Stream[U] = transform(zero, (_: S) ⇒ Nil)(f)

  def transform[S, U](zero: S, onComplete: S ⇒ immutable.Seq[U])(f: (S, O) ⇒ (S, immutable.Seq[U])): Stream[U] =
    andThen(Transform(
      zero,
      f.asInstanceOf[(Any, Any) ⇒ (Any, immutable.Seq[Any])],
      onComplete.asInstanceOf[Any ⇒ immutable.Seq[Any]]))

  def toProducer(generator: ProcessorGenerator): Producer[O] = generator.toProducer(producer, ops)
}

/**
 * INTERNAL API
 */
private[akka] class ActorBasedProcessorGenerator(settings: GeneratorSettings, context: ActorRefFactory) extends ProcessorGenerator {
  import Ast._

  // Ops come in reverse order
  override def toProducer[I, O](producerToExtend: Producer[I], ops: List[AstNode]): Producer[O] = {
    @tailrec def processorChain(topConsumer: Consumer[Any], ops: immutable.Seq[AstNode], bottomProducer: Producer[Any]): (Consumer[Any], Producer[Any]) = {
      ops match {
        case op :: tail ⇒
          val opProcessor: Processor[Any, Any] = processorForNode(op)
          opProcessor.getPublisher.subscribe(topConsumer.getSubscriber)
          processorChain(opProcessor, tail, bottomProducer)
        case _ ⇒ (topConsumer, bottomProducer)
      }
    }

    if (ops.isEmpty) producerToExtend.asInstanceOf[Producer[O]]
    else {
      val opProcessor: Processor[Any, Any] = processorForNode(ops.head)
      val (topConsumer, bottomProducer) = processorChain(opProcessor, ops.tail, opProcessor)
      producerToExtend.getPublisher.subscribe(topConsumer.getSubscriber.asInstanceOf[Subscriber[I]])
      bottomProducer.asInstanceOf[Producer[O]]
    }
  }

  private def processorForNode(op: AstNode): Processor[Any, Any] = op match {
    case t: Transform ⇒ ActorProcessor(context.actorOf(TransformProcessorImpl.props(settings, t)))
    // FIXME implement the other operations as specialized ActorProcessors instead of using transform
  }

}

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {
  case class OnSubscribe(subscription: Subscription)
  // TODO performance improvement: skip wrapping ordinary elements in OnNext
  case class OnNext(element: Any)
  case object OnComplete
  case class OnError(cause: Throwable)

  case object SubscribePending

  case class RequestMore(subscription: ActorSubscription, demand: Int)
  case class Cancel(subscriptions: ActorSubscription)

  case class ExposedPublisher(publisher: ActorPublisher[Any])

  def apply[I, O](ref: ActorRef): Processor[I, O] = new ActorProcessor[I, O](ref)

  trait ActorConsumer[T] extends Consumer[T] {
    def impl: ActorRef
    override val getSubscriber: Subscriber[T] = new ActorSubscriber[T](impl)
  }

  trait ActorProducer[T] extends Producer[T] {
    def impl: ActorRef
    override val getPublisher: Publisher[T] = {
      val a = new ActorPublisher[T](impl)
      // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
      impl ! ExposedPublisher(a.asInstanceOf[ActorPublisher[Any]])
      a
    }

    def produceTo(consumer: Consumer[T]): Unit =
      getPublisher.subscribe(consumer.getSubscriber)
  }

  class ActorProcessor[I, O]( final val impl: ActorRef) extends Processor[I, O] with ActorConsumer[I] with ActorProducer[O]

  class ActorSubscriber[T]( final val impl: ActorRef) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = impl ! OnError(cause)
    override def onComplete(): Unit = impl ! OnComplete
    override def onNext(element: T): Unit = impl ! OnNext(element)
    override def onSubscribe(subscription: Subscription): Unit = impl ! OnSubscribe(subscription)
  }

  final class ActorPublisher[T](val impl: ActorRef) extends Publisher[T] {

    // The subscriber of an subscription attempt is first placed in this list of pending subscribers.
    // The actor will call takePendingSubscribers to remove it from the list when it has received the 
    // SubscribePending message. The AtomicReference is set to null by the shutdown method, which is
    // called by the actor from postStop. Pending (unregistered) subscription attempts are denied by
    // the shutdown method. Subscription attempts after shutdown can be denied immediately.
    private val pendingSubscribers = new AtomicReference[List[Subscriber[T]]](Nil)

    override def subscribe(subscriber: Subscriber[T]): Unit = {
      @tailrec def doSubscribe(subscriber: Subscriber[T]): Unit = {
        val current = pendingSubscribers.get
        if (current eq null)
          reportShutdownError(subscriber)
        else {
          if (pendingSubscribers.compareAndSet(current, subscriber :: current))
            impl ! SubscribePending
          else
            doSubscribe(subscriber) // CAS retry
        }
      }

      doSubscribe(subscriber)
    }

    def takePendingSubscribers(): List[Subscriber[T]] =
      pendingSubscribers.getAndSet(Nil)

    def shutdown(): Unit =
      pendingSubscribers.getAndSet(null) foreach reportShutdownError

    private def reportShutdownError(subscriber: Subscriber[T]): Unit =
      subscriber.onError(new IllegalStateException("Cannot subscribe to shut-down spi.Publisher"))

  }

  class ActorSubscription( final val impl: ActorRef, final val _subscriber: Subscriber[Any]) extends SubscriptionWithCursor {
    override def subscriber[T]: Subscriber[T] = _subscriber.asInstanceOf[Subscriber[T]]
    override def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else impl ! RequestMore(this, elements)
    override def cancel(): Unit = impl ! Cancel(this)
    override def toString = "ActorSubscription"
  }
}

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: GeneratorSettings) extends Actor with SubscriberManagement[Any] {
  import ActorProcessor._
  type S = ActorSubscription

  override def maxBufferSize: Int = settings.maxFanOutBufferSize
  override def initialBufferSize: Int = settings.initialFanOutBufferSize
  override def createSubscription(subscriber: Subscriber[Any]): S = new ActorSubscription(self, subscriber)

  override def receive = waitingExposedPublisher

  //////////////////////  Startup phases //////////////////////

  var upstream: Subscription = _
  var exposedPublisher: ActorPublisher[Any] = _

  def waitingExposedPublisher: Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.become(waitingForUpstream)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForUpstream: Receive = downstreamManagement orElse {
    case OnComplete ⇒ shutdown() // There is nothing to flush here
    case OnSubscribe(subscription) ⇒
      assert(subscription != null)
      upstream = subscription
      // Prime up input buffer
      upstream.requestMore(inputBuffer.length)
      context.become(running)
    case OnError(cause) ⇒ fail(cause)
  }

  //////////////////////  Management of subscribers //////////////////////

  // All methods called here are implemented by SubscriberManagement
  val downstreamManagement: Receive = {
    case SubscribePending                    ⇒ subscribePending()
    case RequestMore(subscription, elements) ⇒ moreRequested(subscription, elements)
    case Cancel(subscription)                ⇒ unregisterSubscription(subscription)
  }

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  //////////////////////  Active state //////////////////////

  def running: Receive = downstreamManagement orElse {
    case OnNext(element) ⇒
      enqueueInputElement(element)
      pump()
    case OnComplete     ⇒ flushAndComplete()
    case OnError(cause) ⇒ fail(cause)
  }

  // Called by SubscriberManagement when all subscribers are gone.
  // The method shutdown() is called automatically by SubscriberManagement after it called this method.
  override def cancelUpstream(): Unit = if (upstream ne null) upstream.cancel()

  // Called by SubscriberManagement whenever the output buffer is ready to accept additional elements
  override protected def requestFromUpstream(elements: Int): Unit = {
    downstreamBufferSpace += elements
    pump()
  }

  def fail(e: Throwable): Unit = {
    abortDownstream(e)
    if (upstream ne null) upstream.cancel()
    context.stop(self)
  }

  var downstreamBufferSpace = 0
  var inputBuffer = Array.ofDim[Any](settings.initialInputBufferSize)
  var inputBufferElements = 0
  var nextInputElementCursor = 0
  val IndexMask = settings.initialInputBufferSize - 1

  // TODO: buffer and batch sizing heuristics
  def requestBatchSize = math.max(1, inputBuffer.length / 2)
  var batchRemaining = requestBatchSize

  def dequeueInputElement(): Any = {
    val elem = inputBuffer(nextInputElementCursor & IndexMask)

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

  def enqueueInputElement(elem: Any): Unit = {
    inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem
    inputBufferElements += 1
  }

  def enqueueOutputElement(elem: Any): Unit = {
    downstreamBufferSpace -= 1
    pushToDownstream(elem)
  }

  // States of the operation that is executed by this processor
  trait TransferState {
    def isReady: Boolean
    def isCompleted: Boolean
    def isExecutable = isReady && !isCompleted
    def inputsAvailable = inputBufferElements > 0
    def demandAvailable = downstreamBufferSpace > 0
    def inputsDepleted = upstreamCompleted && inputBufferElements == 0
  }
  object NeedsInput extends TransferState {
    def isReady = inputsAvailable
    def isCompleted = inputsDepleted
  }
  object NeedsDemand extends TransferState {
    def isReady = demandAvailable
    def isCompleted = false
  }
  object NeedsInputAndDemand extends TransferState {
    def isReady = inputsAvailable && demandAvailable
    def isCompleted = inputsDepleted
  }

  var transferState: TransferState = NeedsInputAndDemand

  // Exchange input buffer elements and output buffer "requests" until one of them becomes empty.
  // Generate upstream requestMore for every Nth consumed input element
  var pumping = false
  def pump(): Unit = {
    if (!pumping) {
      // Prevent reentry from SubscriberManagement
      pumping = true
      try while (transferState.isExecutable)
        transferState = transfer()
      catch { case NonFatal(e) ⇒ fail(e) }
      pumping = false

      if (transferState.isCompleted) {
        isShuttingDown = true
        completeDownstream()
      }

    }
  }

  // Needs to be implemented by Processor implementations. Transfers elements from the input buffer to the output
  // buffer.
  def transfer(): TransferState

  //////////////////////  Completing and Flushing  //////////////////////

  var upstreamCompleted = false

  def flushAndComplete(): Unit = {
    upstreamCompleted = true
    context.become(flushing)
    pump()
  }

  def flushing: Receive = downstreamManagement orElse {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
    case _                         ⇒ // ignore everything else
  }

  //////////////////////  Shutdown and cleanup (graceful and abort) //////////////////////

  var isShuttingDown = false

  // Called by SubscriberManagement to signal that output buffer finished (flushed or aborted)
  override def shutdown(): Unit = {
    isShuttingDown = true
    context.stop(self)
  }

  override def postStop(): Unit = {
    if (exposedPublisher ne null)
      exposedPublisher.shutdown()
    // Non-gracefully stopped, do our best here
    if (!isShuttingDown)
      abortDownstream(new IllegalStateException("Processor actor terminated abruptly"))

    // FIXME what about upstream subscription before we got 
    // case OnSubscribe(subscription) ⇒ subscription.cancel()  
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    throw new IllegalStateException("This actor cannot be restarted")
  }

}

/**
 * INTERNAL API
 */
private[akka] object TransformProcessorImpl {
  def props(settings: GeneratorSettings, op: Ast.Transform): Props =
    Props(new TransformProcessorImpl(settings, op))
}

/**
 * INTERNAL API
 */
private[akka] class TransformProcessorImpl(_settings: GeneratorSettings, op: Ast.Transform) extends ActorProcessorImpl(_settings) {
  var state = op.zero
  // TODO performance improvement: mutable buffer?
  var emits = immutable.Seq.empty[Any]

  override def transfer(): TransferState = {
    if (emits.isEmpty) {
      val (nextState, newEmits) = op.f(state, dequeueInputElement())
      state = nextState
      emits = newEmits
    } else {
      enqueueOutputElement(emits.head)
      emits = emits.tail
    }

    if (emits.nonEmpty) NeedsDemand
    else NeedsInputAndDemand
  }
}