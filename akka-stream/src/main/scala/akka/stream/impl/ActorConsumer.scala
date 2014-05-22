/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal
import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.{ Subscriber, Subscription }
import Ast.{ AstNode, Transform }
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, actorRef2Scala }
import akka.stream.MaterializerSettings
import akka.stream.Transformer
import akka.stream.actor.ActorConsumer.{ OnNext, OnError, OnComplete, OnSubscribe }

/**
 * INTERNAL API
 */
private[akka] object ActorConsumerProps {
  import Ast._

  def props(settings: MaterializerSettings, op: AstNode) = op match {
    case t: Transform ⇒ Props(new TransformActorConsumer(settings, t.transformer)) withDispatcher (settings.dispatcher)
  }
}

/**
 * INTERNAL API
 */
private[akka] abstract class AbstractActorConsumer(val settings: MaterializerSettings) extends Actor with SoftShutdown {
  import ActorProcessor._
  import ActorBasedFlowMaterializer._

  /**
   * Consume one element synchronously: the Actor mailbox is the queue.
   */
  def onNext(elem: Any): Unit

  /**
   * Must call shutdown() eventually.
   */
  def onError(e: Throwable): Unit

  /**
   * Must call shutdown() eventually.
   */
  def onComplete(): Unit

  /**
   * Terminate processing after the current message; will cancel the subscription if necessary.
   */
  def shutdown(): Unit = softShutdown()

  context.setReceiveTimeout(settings.upstreamSubscriptionTimeout)

  final def receive = {
    case OnSubscribe(sub) ⇒
      context.setReceiveTimeout(Duration.Undefined)
      subscription = Some(sub)
      requestMore()
      context.become(active)
    case OnError(cause) ⇒
      withCtx(context)(onError(cause))
    case OnComplete ⇒
      withCtx(context)(onComplete())
  }

  private var subscription: Option[Subscription] = None

  private val highWatermark = settings.maximumInputBufferSize
  private val lowWatermark = Math.max(1, highWatermark / 2)
  private var requested = 0
  private def requestMore(): Unit =
    if (requested < lowWatermark) {
      val amount = highWatermark - requested
      subscription.get.requestMore(amount)
      requested += amount
    }
  private def gotOne(): Unit = {
    requested -= 1
    requestMore()
  }

  final def active: Receive = {
    case OnSubscribe(sub) ⇒ sub.cancel()
    case OnNext(elem)     ⇒ { gotOne(); withCtx(context)(onNext(elem)) }
    case OnError(cause)   ⇒ { subscription = None; withCtx(context)(onError(cause)) }
    case OnComplete       ⇒ { subscription = None; withCtx(context)(onComplete()) }
  }

  override def postStop(): Unit = {
    subscription foreach (_.cancel())
  }
}

/**
 * INTERNAL API
 */
private[akka] class TransformActorConsumer(_settings: MaterializerSettings, transformer: Transformer[Any, Any]) extends AbstractActorConsumer(_settings) with ActorLogging {

  var error: Option[Throwable] = None // Null is the proper default here

  var hasCleanupRun = false
  private var onCompleteCalled = false
  private def callOnComplete(): Unit = {
    if (!onCompleteCalled) {
      onCompleteCalled = true
      try transformer.onTermination(error)
      catch { case NonFatal(e) ⇒ log.error(e, "failure during onTermination") }
      shutdown()
    }
  }

  override def onNext(elem: Any): Unit = {
    transformer.onNext(elem)
    if (transformer.isComplete)
      callOnComplete()
  }

  override def onError(cause: Throwable): Unit = {
    try {
      transformer.onError(cause)
      error = Some(cause)
      onComplete()
    } catch {
      case NonFatal(e) ⇒
        log.error(e, "terminating due to onError")
        shutdown()
    }
  }

  override def onComplete(): Unit = {
    callOnComplete()
  }

  override def softShutdown(): Unit = {
    transformer.cleanup()
    hasCleanupRun = true // for postStop
    super.softShutdown()
  }

  override def postStop(): Unit = {
    try super.postStop() finally if (!hasCleanupRun) transformer.cleanup()
  }
}
