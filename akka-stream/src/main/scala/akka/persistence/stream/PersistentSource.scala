/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.stream

import akka.actor._
import akka.persistence._
import akka.stream.MaterializerSettings
import akka.stream.impl.ActorPublisher
import akka.stream.impl.ActorSubscriptionWithCursor
import akka.stream.impl.Cancel
import akka.stream.impl.ExposedPublisher
import akka.stream.impl.RequestMore
import akka.stream.impl.SoftShutdown
import akka.stream.impl.SubscribePending
import akka.stream.impl.SubscriberManagement
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.scaladsl.KeyedActorFlowSource
import org.reactivestreams.Subscriber

import scala.concurrent.duration._
import scala.util.control.NonFatal

// ------------------------------------------------------------------------------------------------
// FIXME: #15964 move this file to akka-persistence-experimental once going back to project dependencies
// ------------------------------------------------------------------------------------------------

/**
 * Constructs a `Source` from the given [[akka.persistence.PersistentActor]],
 * identified by `persistenceId`. Events are pulled from the persistent actor's
 * journal (using a [[akka.persistence.PersistentView]]) in accordance with the
 * demand coming from the downstream transformation steps.
 *
 * Elements pulled from the persistent actor's journal are buffered in memory so that
 * fine-grained demands (requests) from downstream can be served efficiently.
 *
 * Reads from the journal are done in (coarse-grained) batches of configurable
 * size (which correspond to the configurable maximum buffer size).
 *
 * @see [[akka.persistence.stream.PersistentSourceSettings]]
 */
final case class PersistentSource[Out](persistenceId: String, sourceSettings: PersistentSourceSettings = PersistentSourceSettings()) extends KeyedActorFlowSource[Out, ActorRef] {

  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val (publisher, publisherRef) = create(materializer, flowName)
    publisher.subscribe(flowSubscriber)
    publisherRef
  }
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val publisherRef = materializer.actorOf(PersistentSourceImpl.props(persistenceId, sourceSettings, materializer.settings), name = s"$flowName-0-persistent-source")
    (ActorPublisher[Out](publisherRef), publisherRef)
  }
}

/**
 * Configuration object for a `PersistentSource`.
 *
 * @param fromSequenceNr Sequence number where the published stream shall start (inclusive).
 *                       Default is `1L`.
 * @param maxBufferSize Maximum number of persistent events to be buffered in memory (per Source).
 *                      Default is `100`.
 * @param idle Optional duration to wait if no more persistent events can be pulled from the journal
 *             before attempting the next pull. Default is `None` which causes the publisher to take
 *             the value defined by the `akka.persistence.view.auto-update-interval` configuration
 *             key. If defined, the `idle` value is taken directly.
 */
case class PersistentSourceSettings(fromSequenceNr: Long = 1L, maxBufferSize: Int = 100, idle: Option[FiniteDuration] = None) {
  require(fromSequenceNr > 0L, "fromSequenceNr must be > 0")
}

private object PersistentSourceImpl {
  def props(persistenceId: String, sourceSettings: PersistentSourceSettings, settings: MaterializerSettings): Props =
    Props(classOf[PersistentSourceImpl], persistenceId, sourceSettings, settings).withDispatcher(settings.dispatcher)
}

private class PersistentSourceImpl(persistenceId: String, sourceSettings: PersistentSourceSettings, materializerSettings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SubscriberManagement[Any]
  with SoftShutdown {

  import PersistentSourceBuffer._

  type S = ActorSubscriptionWithCursor[Any]

  private val buffer = context.actorOf(Props(classOf[PersistentSourceBuffer], persistenceId, sourceSettings, self).
    withDispatcher(context.props.dispatcher), "persistent-source-buffer")

  private var pub: ActorPublisher[Any] = _
  private var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  final def receive = {
    case ExposedPublisher(publisher) ⇒
      pub = publisher.asInstanceOf[ActorPublisher[Any]]
      context.become(waitingForSubscribers)
  }

  final def waitingForSubscribers: Receive = {
    case SubscribePending ⇒
      pub.takePendingSubscribers() foreach registerSubscriber
      context.become(active)
  }

  final def active: Receive = {
    case SubscribePending ⇒
      pub.takePendingSubscribers() foreach registerSubscriber
    case RequestMore(sub, elements) ⇒
      moreRequested(sub.asInstanceOf[S], elements)
    case Cancel(sub) ⇒
      unregisterSubscription(sub.asInstanceOf[S])
    case Response(ps) ⇒
      try ps.foreach(pushToDownstream) catch {
        case NonFatal(e) ⇒ abortDownstream(e); shutdownReason = Some(e)
      }
  }

  override def requestFromUpstream(elements: Long): Unit =
    buffer ! Request(elements)

  override def initialBufferSize =
    materializerSettings.initialFanOutBufferSize

  override def maxBufferSize =
    materializerSettings.maxFanOutBufferSize

  override def createSubscription(subscriber: Subscriber[_ >: Any]): ActorSubscriptionWithCursor[Any] =
    new ActorSubscriptionWithCursor(self, subscriber)

  override def cancelUpstream(): Unit = {
    if (pub ne null) pub.shutdown(shutdownReason)
    context.stop(buffer)
    softShutdown()
  }
  override def shutdown(completed: Boolean): Unit = {
    if (pub ne null) pub.shutdown(shutdownReason)
    context.stop(buffer)
    softShutdown()
  }

  override def postStop(): Unit = {
    if (pub ne null) pub.shutdown(shutdownReason)
  }
}

private object PersistentSourceBuffer {
  case class Request(n: Long)
  case class Response(events: Vector[Any])

  case object Fill
  case object Filled
}

/**
 * A view that buffers up to `sourceSettings.maxBufferSize` persistent events in memory.
 * Downstream demands (requests) are served if the buffer is non-empty either while filling
 * the buffer or after having filled the buffer. When the buffer becomes empty new persistent
 * events are loaded from the journal (in batches up to `sourceSettings.maxBufferSize`).
 */
private class PersistentSourceBuffer(override val persistenceId: String, sourceSettings: PersistentSourceSettings, publisher: ActorRef) extends PersistentView {
  import PersistentSourceBuffer._
  import context.dispatcher

  private var replayed = 0L
  private var pendingDemand = 0L
  private var buffer: Vector[Any] = Vector.empty

  override def viewId: String = persistenceId + "-stream-view"

  private val filling: Receive = {
    case Filled ⇒
      if (buffer.nonEmpty && pendingDemand > 0) respond(pendingDemand)
      if (buffer.nonEmpty) pause()
      else if (replayed > 0) fill()
      else schedule()
    case Request(num) ⇒
      pendingDemand += num
      if (buffer.nonEmpty) respond(pendingDemand)
    case persistentEvent ⇒
      buffer :+= persistentEvent
      replayed += 1
      if (pendingDemand > 0) respond(pendingDemand)
  }

  private val pausing: Receive = {
    case Request(num) ⇒
      pendingDemand += num
      respond(pendingDemand)
      if (buffer.isEmpty) fill()
  }

  private val scheduled: Receive = {
    case Fill ⇒
      fill()
    case Request(num) ⇒
      pendingDemand += num
  }

  def receive = filling

  override def onReplaySuccess(receive: Receive, await: Boolean): Unit = {
    super.onReplaySuccess(receive, await)
    self ! Filled
  }

  override def onReplayFailure(receive: Receive, await: Boolean, cause: Throwable): Unit = {
    super.onReplayFailure(receive, await, cause)
    self ! Filled
  }

  override def lastSequenceNr: Long =
    math.max(sourceSettings.fromSequenceNr - 1L, super.lastSequenceNr)

  override def autoUpdateInterval: FiniteDuration =
    sourceSettings.idle.getOrElse(super.autoUpdateInterval)

  override def autoUpdateReplayMax: Long =
    sourceSettings.maxBufferSize

  override def autoUpdate: Boolean =
    false

  private def fill(): Unit = {
    replayed = 0
    context.become(filling)
    self ! Update(await = false, autoUpdateReplayMax)
  }

  private def pause(): Unit = {
    context.become(pausing)
  }

  private def schedule(): Unit = {
    context.become(scheduled)
    context.system.scheduler.scheduleOnce(autoUpdateInterval, self, Fill)
  }

  // TODO Breaks now?
  private def respond(num: Long): Unit = {
    if (num <= Int.MaxValue) {
      val n = num.toInt
      val (res, buf) = buffer.splitAt(n)
      publisher ! Response(res)
      buffer = buf
      pendingDemand -= res.size
    } else {
      respond(Int.MaxValue)
    }
  }
}
