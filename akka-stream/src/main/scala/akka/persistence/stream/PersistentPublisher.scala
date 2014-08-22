/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.stream

import scala.util.control.NonFatal
import scala.concurrent.duration._

import org.reactivestreams.{ Publisher, Subscriber }

import akka.actor._
import akka.persistence._
import akka.stream._
import akka.stream.impl._
import akka.stream.impl.Ast.PublisherNode
import akka.stream.scaladsl.Flow

// ------------------------------------------------------------------------------------------------
// FIXME: move this file to akka-persistence-experimental once going back to project dependencies
// ------------------------------------------------------------------------------------------------

object PersistentFlow {
  /**
   * Starts a new event flow from the given [[akka.persistence.PersistentActor]],
   * identified by `persistenceId`. Events are pulled from the peristent actor's
   * journal (using a [[akka.persistence.PersistentView]]) in accordance with the
   * demand coming from the downstream transformation steps.
   *
   * Elements pulled from the peristent actor's journal are buffered in memory so that
   * fine-grained demands (requests) from downstream can be served efficiently.
   */
  def fromPersistentActor(persistenceId: String): Flow[Any] =
    fromPersistentActor(persistenceId, PersistentPublisherSettings())

  /**
   * Starts a new event flow from the given [[akka.persistence.PersistentActor]],
   * identified by `persistenceId`. Events are pulled from the peristent actor's
   * journal (using a [[akka.persistence.PersistentView]]) in accordance with the
   * demand coming from the downstream transformation steps.
   *
   * Elements pulled from the peristent actor's journal are buffered in memory so that
   * fine-grained demands (requests) from downstream can be served efficiently.
   *
   * Reads from the journal are done in (coarse-grained) batches of configurable
   * size (which correspond to the configurable maximum buffer size).
   *
   * @see [[akka.persistence.PersistentPublisherSettings]]
   */
  def fromPersistentActor(persistenceId: String, publisherSettings: PersistentPublisherSettings): Flow[Any] =
    FlowImpl(PersistentPublisherNode(persistenceId, publisherSettings), Nil)
}

/**
 * Configuration object for a persistent stream publisher.
 *
 * @param fromSequenceNr Sequence number where the published stream shall start (inclusive).
 *                       Default is `1L`.
 * @param maxBufferSize Maximum number of persistent events to be buffered in memory (per publisher).
 *                      Default is `100`.
 * @param idle Optional duration to wait if no more persistent events can be pulled from the journal
 *             before attempting the next pull. Default is `None` which causes the publisher to take
 *             the value defined by the `akka.persistence.view.auto-update-interval` configuration
 *             key. If defined, the `idle` value is taken directly.
 */
case class PersistentPublisherSettings(fromSequenceNr: Long = 1L, maxBufferSize: Int = 100, idle: Option[FiniteDuration] = None) {
  require(fromSequenceNr > 0L, "fromSequenceNr must be > 0")
}

private object PersistentPublisher {
  def props(persistenceId: String, publisherSettings: PersistentPublisherSettings, settings: MaterializerSettings): Props =
    Props(classOf[PersistentPublisherImpl], persistenceId, publisherSettings, settings).withDispatcher(settings.dispatcher)
}

private case class PersistentPublisherNode(persistenceId: String, publisherSettings: PersistentPublisherSettings) extends PublisherNode[Any] {
  def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Any] =
    ActorPublisher[Any](materializer.actorOf(PersistentPublisher.props(persistenceId, publisherSettings, materializer.settings),
      name = s"$flowName-0-persistentPublisher"))
}

private class PersistentPublisherImpl(persistenceId: String, publisherSettings: PersistentPublisherSettings, materializerSettings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SubscriberManagement[Any]
  with SoftShutdown {

  import ActorBasedFlowMaterializer._
  import PersistentPublisherBuffer._

  type S = ActorSubscription[Any]

  private val buffer = context.actorOf(Props(classOf[PersistentPublisherBuffer], persistenceId, publisherSettings, self).
    withDispatcher(context.props.dispatcher), "publisherBuffer")

  private var pub: ActorPublisher[Any] = _
  private var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  final def receive = {
    case ExposedPublisher(pub) ⇒
      this.pub = pub.asInstanceOf[ActorPublisher[Any]]
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
      try {
        ps.foreach(pushToDownstream)
      } catch {
        case Stop        ⇒ { completeDownstream(); shutdownReason = None }
        case NonFatal(e) ⇒ { abortDownstream(e); shutdownReason = Some(e) }
      }
  }

  override def requestFromUpstream(elements: Int): Unit =
    buffer ! Request(elements)

  override def initialBufferSize =
    materializerSettings.initialFanOutBufferSize

  override def maxBufferSize =
    materializerSettings.maxFanOutBufferSize

  override def createSubscription(subscriber: Subscriber[Any]): ActorSubscription[Any] =
    new ActorSubscription(self, subscriber)

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

private object PersistentPublisherBuffer {
  case class Request(num: Int)
  case class Response(events: Vector[Any])

  case object Fill
  case object Filled
}

/**
 * A view that buffers up to `publisherSettings.maxBufferSize` persistent events in memory.
 * Downstream demands (requests) are served if the buffer is non-empty either while filling
 * the buffer or after having filled the buffer. When the buffer becomes empty new persistent
 * events are loaded from the journal (in batches up to `publisherSettings.maxBufferSize`).
 */
private class PersistentPublisherBuffer(override val persistenceId: String, publisherSettings: PersistentPublisherSettings, publisher: ActorRef) extends PersistentView {
  import PersistentPublisherBuffer._
  import context.dispatcher

  private var replayed = 0
  private var requested = 0
  private var buffer: Vector[Any] = Vector.empty

  override def viewId: String = persistenceId + "-stream-view"

  private val filling: Receive = {
    case Filled ⇒
      if (buffer.nonEmpty && requested > 0) respond(requested)
      if (buffer.nonEmpty) pause()
      else if (replayed > 0) fill()
      else schedule()
    case Request(num) ⇒
      requested += num
      if (buffer.nonEmpty) respond(requested)
    case persistentEvent ⇒
      buffer :+= persistentEvent
      replayed += 1
      if (requested > 0) respond(requested)
  }

  private val pausing: Receive = {
    case Request(num) ⇒
      requested += num
      respond(requested)
      if (buffer.isEmpty) fill()
  }

  private val scheduled: Receive = {
    case Fill ⇒
      fill()
    case Request(num) ⇒
      requested += num
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
    math.max(publisherSettings.fromSequenceNr - 1L, super.lastSequenceNr)

  override def autoUpdateInterval: FiniteDuration =
    publisherSettings.idle.getOrElse(super.autoUpdateInterval)

  override def autoUpdateReplayMax: Long =
    publisherSettings.maxBufferSize

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

  private def respond(num: Int): Unit = {
    val (res, buf) = buffer.splitAt(num)
    publisher ! Response(res)
    buffer = buf
    requested -= res.size
  }
}
