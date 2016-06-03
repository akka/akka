package akka.stream.impl

import akka.actor.{ Deploy, Props, Actor, ActorRef }
import akka.stream.ActorMaterializerSettings
import org.reactivestreams.Subscriber

/**
 * INTERNAL API
 */
private[akka] abstract class FanoutOutputs(
  val maxBufferSize:     Int,
  val initialBufferSize: Int,
  self:                  ActorRef,
  val pump:              Pump)
  extends DefaultOutputTransferStates
  with SubscriberManagement[Any] {

  override type S = ActorSubscriptionWithCursor[_ >: Any]
  override def createSubscription(subscriber: Subscriber[_ >: Any]): S =
    new ActorSubscriptionWithCursor(self, subscriber)

  protected var exposedPublisher: ActorPublisher[Any] = _

  private var downstreamBufferSpace: Long = 0L
  private var downstreamCompleted = false
  override def demandAvailable = downstreamBufferSpace > 0
  override def demandCount: Long = downstreamBufferSpace

  override val subreceive = new SubReceive(waitingExposedPublisher)

  def enqueueOutputElement(elem: Any): Unit = {
    ReactiveStreamsCompliance.requireNonNullElement(elem)
    downstreamBufferSpace -= 1
    pushToDownstream(elem)
  }

  override def complete(): Unit =
    if (!downstreamCompleted) {
      downstreamCompleted = true
      completeDownstream()
    }

  override def cancel(): Unit = complete()

  override def error(e: Throwable): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      abortDownstream(e)
      if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
    }
  }

  def isClosed: Boolean = downstreamCompleted

  def afterShutdown(): Unit

  override protected def requestFromUpstream(elements: Long): Unit = downstreamBufferSpace += elements

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  override protected def shutdown(completed: Boolean): Unit = {
    if (exposedPublisher ne null) {
      if (completed) exposedPublisher.shutdown(None)
      else exposedPublisher.shutdown(ActorPublisher.SomeNormalShutdownReason)
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
      moreRequested(subscription.asInstanceOf[ActorSubscriptionWithCursor[Any]], elements)
      pump.pump()
    case Cancel(subscription) ⇒
      unregisterSubscription(subscription.asInstanceOf[ActorSubscriptionWithCursor[Any]])
      pump.pump()
  }

}

private[akka] object FanoutProcessorImpl {
  def props(actorMaterializerSettings: ActorMaterializerSettings): Props =
    Props(new FanoutProcessorImpl(actorMaterializerSettings)).withDeploy(Deploy.local)
}
/**
 * INTERNAL API
 */
private[akka] class FanoutProcessorImpl(_settings: ActorMaterializerSettings)
  extends ActorProcessorImpl(_settings) {

  override val primaryOutputs: FanoutOutputs =
    new FanoutOutputs(settings.maxInputBufferSize, settings.initialInputBufferSize, self, this) {
      override def afterShutdown(): Unit = afterFlush()
    }

  val running: TransferPhase = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
  }

  override def fail(e: Throwable): Unit = {
    if (settings.debugLogging)
      log.debug("fail due to: {}", e.getMessage)
    primaryInputs.cancel()
    primaryOutputs.error(e)
    // Stopping will happen after flush
  }

  override def pumpFinished(): Unit = {
    primaryInputs.cancel()
    primaryOutputs.complete()
  }

  def afterFlush(): Unit = context.stop(self)

  initialPhase(1, running)
}
