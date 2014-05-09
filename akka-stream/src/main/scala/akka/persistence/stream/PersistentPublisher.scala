/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.stream

import scala.util.control.NonFatal
import scala.concurrent.duration._

import org.reactivestreams.api.Producer
import org.reactivestreams.spi.Subscriber

import akka.actor._
import akka.persistence._
import akka.stream._
import akka.stream.impl._
import akka.stream.impl.Ast.ProducerNode
import akka.stream.scaladsl.Flow

// ------------------------------------------------------------------------------------------------
// FIXME: move this file to akka-persistence-experimental once going back to project dependencies
// NOTE: "producer" has been changed to "publisher" wherever possible, covering the upcoming
//       changes in reactive-streams.
// ------------------------------------------------------------------------------------------------

object PersistentFlow {
  /**
   * Starts a new [[akka.persistence.Persistent]] message flow from the given processor,
   * identified by `processorId`. Elements are pulled from the processor's
   * journal (using a [[akka.persistence.View]]) in accordance with the demand coming from
   * the downstream transformation steps.
   *
   * Elements pulled from the processor's journal are buffered in memory so that
   * fine-grained demands (requests) from downstream can be served efficiently.
   */
  def fromProcessor(processorId: String): Flow[Persistent] =
    fromProcessor(processorId, PersistentPublisherSettings())

  /**
   * Starts a new [[akka.persistence.Persistent]] message flow from the given processor,
   * identified by `processorId`. Elements are pulled from the processor's
   * journal (using a [[akka.persistence.View]]) in accordance with the demand coming from
   * the downstream transformation steps.
   *
   * Elements pulled from the processor's journal are buffered in memory so that
   * fine-grained demands (requests) from downstream can be served efficiently.
   * Reads from the journal are done in (coarse-grained) batches of configurable
   * size (which correspond to the configurable maximum buffer size).
   *
   * @see [[akka.persistence.PersistentPublisherSettings]]
   */
  def fromProcessor(processorId: String, publisherSettings: PersistentPublisherSettings): Flow[Persistent] =
    FlowImpl(PersistentPublisherNode(processorId, publisherSettings), Nil)
}

/**
 * Configuration object for a [[akka.persistence.Persistent]] stream publisher.
 *
 * @param fromSequenceNr Sequence number where the published stream shall start (inclusive).
 *                       Default is `1L`.
 * @param maxBufferSize Maximum number of persistent messages to be buffered in memory (per publisher).
 *                      Default is `100`.
 * @param idle Optional duration to wait if no more persistent messages can be pulled from the journal
 *             before attempting the next pull. Default is `None` which causes the publisher to take
 *             the value defined by the `akka.persistence.view.auto-update-interval` configuration
 *             key. If defined, the `idle` value is taken directly.
 */
case class PersistentPublisherSettings(fromSequenceNr: Long = 1L, maxBufferSize: Int = 100, idle: Option[FiniteDuration] = None) {
  require(fromSequenceNr > 0L, "fromSequenceNr must be > 0")
}

private object PersistentPublisher {
  def props(processorId: String, publisherSettings: PersistentPublisherSettings, settings: MaterializerSettings): Props =
    Props(classOf[PersistentPublisherImpl], processorId, publisherSettings, settings)
}

private case class PersistentPublisherNode(processorId: String, publisherSettings: PersistentPublisherSettings) extends ProducerNode[Persistent] {
  def createProducer(materializer: ActorBasedFlowMaterializer, flowName: String): Producer[Persistent] =
    new ActorProducer(materializer.context.actorOf(PersistentPublisher.props(processorId, publisherSettings, materializer.settings),
      name = s"$flowName-0-persistentPublisher"))
}

private class PersistentPublisherImpl(processorId: String, publisherSettings: PersistentPublisherSettings, materializerSettings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SubscriberManagement[Persistent]
  with SoftShutdown {

  import ActorBasedFlowMaterializer._
  import PersistentPublisherBuffer._

  type S = ActorSubscription[Persistent]

  private val buffer = context.actorOf(Props(classOf[PersistentPublisherBuffer], processorId, publisherSettings, self), "publisherBuffer")

  private var pub: ActorPublisher[Persistent] = _
  private var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  final def receive = {
    case ExposedPublisher(pub) ⇒
      this.pub = pub.asInstanceOf[ActorPublisher[Persistent]]
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

  override def createSubscription(subscriber: Subscriber[Persistent]): ActorSubscription[Persistent] =
    new ActorSubscription(self, subscriber)

  override def cancelUpstream(): Unit = {
    pub.shutdown(shutdownReason)
    context.stop(buffer)
    softShutdown()
  }
  override def shutdown(completed: Boolean): Unit = {
    pub.shutdown(shutdownReason)
    context.stop(buffer)
    softShutdown()
  }

  override def postStop(): Unit = {
    pub.shutdown(shutdownReason)
  }
}

private object PersistentPublisherBuffer {
  case class Request(num: Int)
  case class Response(messages: Vector[Persistent])

  case object Fill
  case object Filled
}

/**
 * A view that buffers up to `publisherSettings.maxBufferSize` persistent messages in memory.
 * Downstream demands (requests) are served if the buffer is non-empty either while filling
 * the buffer or after having filled the buffer. When the buffer becomes empty new persistent
 * messages are loaded from the journal (in batches up to `publisherSettings.maxBufferSize`).
 */
private class PersistentPublisherBuffer(override val processorId: String, publisherSettings: PersistentPublisherSettings, publisher: ActorRef) extends View {
  import PersistentPublisherBuffer._
  import context.dispatcher

  private var replayed = 0
  private var requested = 0
  private var buffer: Vector[Persistent] = Vector.empty

  private val filling: Receive = {
    case p: Persistent ⇒
      buffer :+= p
      replayed += 1
      if (requested > 0) respond(requested)
    case Filled ⇒
      if (buffer.nonEmpty && requested > 0) respond(requested)
      if (buffer.nonEmpty) pause()
      else if (replayed > 0) fill()
      else schedule()
    case Request(num) ⇒
      requested += num
      if (buffer.nonEmpty) respond(requested)
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
