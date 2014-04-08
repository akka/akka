/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.api.Processor
import org.reactivestreams.spi.Subscriber
import akka.actor._
import akka.stream.MaterializerSettings
import akka.event.LoggingReceive
import akka.stream.impl._

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {
  import Ast._
  def props(settings: MaterializerSettings, op: AstNode): Props = op match {
    case t: Transform ⇒ Props(new TransformProcessorImpl(settings, t))
    case r: Recover   ⇒ Props(new RecoverProcessorImpl(settings, r))
    case s: SplitWhen ⇒ Props(new SplitWhenProcessorImpl(settings, s.p))
    case g: GroupBy   ⇒ Props(new GroupByProcessorImpl(settings, g.f))
    case m: Merge     ⇒ Props(new MergeImpl(settings, m.other))
    case z: Zip       ⇒ Props(new ZipImpl(settings, z.other))
    case c: Concat    ⇒ Props(new ConcatImpl(settings, c.next))
  }
}

/**
 * INTERNAL API
 */
private[akka] class ActorProcessor[I, O]( final val impl: ActorRef) extends Processor[I, O] with ActorConsumerLike[I] with ActorProducerLike[O]

/**
 * INTERNAL API
 */
private[akka] trait PrimaryInputs {
  this: Actor ⇒
  // FIXME: have a NoInputs here to avoid nulls
  protected var primaryInputs: Inputs = _

  def initialInputBufferSize: Int
  def maximumInputBufferSize: Int

  def waitingForUpstream: Receive = {
    case OnComplete ⇒
      // Instead of introducing an edge case, handle it in the general way
      primaryInputs = EmptyInputs
      transitionToRunningWhenReady()
    case OnSubscribe(subscription) ⇒
      assert(subscription != null)
      primaryInputs = new BatchingInputBuffer(subscription, initialInputBufferSize)
      transitionToRunningWhenReady()
    case OnError(cause) ⇒ primaryInputOnError(cause)
  }

  def transitionToRunningWhenReady(): Unit =
    if (primaryInputs ne null) {
      primaryInputs.prefetch()
      primaryInputsReady()
    }

  def upstreamManagement: Receive = {
    case OnNext(element) ⇒
      primaryInputs.enqueueInputElement(element)
      pumpInputs()
    case OnComplete ⇒
      primaryInputs.complete()
      primaryInputOnComplete()
      pumpInputs()
    case OnError(cause) ⇒ primaryInputOnError(cause)
  }

  def pumpInputs(): Unit
  def primaryInputsReady(): Unit
  def primaryInputOnError(cause: Throwable): Unit
  def primaryInputOnComplete(): Unit
}

/**
 * INTERNAL API
 */
private[akka] trait PrimaryOutputs {
  this: Actor ⇒
  protected var exposedPublisher: ActorPublisher[Any] = _

  def initialFanOutBufferSize: Int
  def maxFanOutBufferSize: Int

  object PrimaryOutputs extends FanoutOutputs(maxFanOutBufferSize, initialFanOutBufferSize) {
    override type S = ActorSubscription[Any]
    override def createSubscription(subscriber: Subscriber[Any]): ActorSubscription[Any] =
      new ActorSubscription(self, subscriber)
    override def afterShutdown(completed: Boolean): Unit = primaryOutputsFinished(completed)
  }

  def waitingExposedPublisher: Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      primaryOutputsReady()
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def downstreamManagement: Receive = {
    case SubscribePending ⇒
      subscribePending()
    case RequestMore(subscription, elements) ⇒
      PrimaryOutputs.handleRequest(subscription.asInstanceOf[ActorSubscription[Any]], elements)
      pumpOutputs()
    case Cancel(subscription) ⇒
      PrimaryOutputs.removeSubscription(subscription.asInstanceOf[ActorSubscription[Any]])
      pumpOutputs()
  }

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach PrimaryOutputs.addSubscriber

  def primaryOutputsFinished(completed: Boolean): Unit
  def primaryOutputsReady(): Unit

  def pumpOutputs(): Unit

}

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SoftShutdown
  with PrimaryInputs
  with PrimaryOutputs
  with Pump {

  val initialInputBufferSize: Int = settings.initialInputBufferSize
  val maximumInputBufferSize: Int = settings.maximumInputBufferSize
  val initialFanOutBufferSize: Int = settings.initialFanOutBufferSize
  val maxFanOutBufferSize: Int = settings.maxFanOutBufferSize

  override def receive = waitingExposedPublisher

  override def primaryInputOnError(e: Throwable): Unit = fail(e)
  override def primaryInputOnComplete(): Unit = context.become(flushing)
  override def primaryInputsReady(): Unit = {
    setTransferState(initialTransferState)
    context.become(running)
  }

  override def primaryOutputsReady(): Unit = context.become(downstreamManagement orElse waitingForUpstream)
  override def primaryOutputsFinished(completed: Boolean): Unit = {
    isShuttingDown = true
    if (completed)
      shutdownReason = None
    shutdown()
  }

  def running: Receive = LoggingReceive(downstreamManagement orElse upstreamManagement)

  def flushing: Receive = downstreamManagement orElse {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
    case _                         ⇒ // ignore everything else
  }

  protected def fail(e: Throwable): Unit = {
    shutdownReason = Some(e)
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    PrimaryOutputs.cancel(e)
    shutdown()
  }

  lazy val needsPrimaryInputAndDemand = primaryInputs.NeedsInput && PrimaryOutputs.NeedsDemand

  protected def initialTransferState: TransferState

  override val pumpContext = context
  override def pumpInputs(): Unit = pump()
  override def pumpOutputs(): Unit = pump()

  override def pumpFinished(): Unit = {
    if (primaryInputs.isOpen) primaryInputs.cancel()
    context.become(flushing)
    PrimaryOutputs.complete()
  }
  override def pumpFailed(e: Throwable): Unit = fail(e)

  //////////////////////  Shutdown and cleanup (graceful and abort) //////////////////////

  var isShuttingDown = false
  var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  def shutdown(): Unit = {
    if (primaryInputs ne null) primaryInputs.cancel()
    exposedPublisher.shutdown(shutdownReason)
    softShutdown()
  }

  override def postStop(): Unit = {
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(shutdownReason)
    // Non-gracefully stopped, do our best here
    if (!isShuttingDown)
      PrimaryOutputs.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    throw new IllegalStateException("This actor cannot be restarted")
  }

}
