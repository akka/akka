/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.actor._
import akka.stream.ActorFlowMaterializerSettings
import akka.pattern.pipe
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object FuturePublisher {
  def props(future: Future[Any], settings: ActorFlowMaterializerSettings): Props =
    Props(new FuturePublisher(future, settings)).withDispatcher(settings.dispatcher).withDeploy(Deploy.local)

  object FutureSubscription {
    final case class Cancel(subscription: FutureSubscription) extends DeadLetterSuppression with NoSerializationVerificationNeeded
    final case class RequestMore(subscription: FutureSubscription, elements: Long) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  }

  case class FutureValue(value: Any) extends NoSerializationVerificationNeeded

  class FutureSubscription(ref: ActorRef) extends Subscription {
    import akka.stream.impl.FuturePublisher.FutureSubscription._
    def cancel(): Unit = ref ! Cancel(this)
    def request(elements: Long): Unit = ref ! RequestMore(this, elements)
    override def toString = "FutureSubscription"
  }
}

/**
 * INTERNAL API
 */
// FIXME why do we need to have an actor to drive a Future?
private[akka] class FuturePublisher(future: Future[Any], settings: ActorFlowMaterializerSettings) extends Actor {
  import akka.stream.impl.FuturePublisher._
  import akka.stream.impl.FuturePublisher.FutureSubscription.Cancel
  import akka.stream.impl.FuturePublisher.FutureSubscription.RequestMore
  import ReactiveStreamsCompliance._

  var exposedPublisher: ActorPublisher[Any] = _
  var subscribers = Map.empty[Subscriber[Any], FutureSubscription]
  var subscriptions = Map.empty[FutureSubscription, Subscriber[Any]]
  var subscriptionsReadyForPush = Set.empty[FutureSubscription]
  var futureValue: Option[Try[Any]] = future.value
  var shutdownReason: Option[Throwable] = ActorPublisher.SomeNormalShutdownReason

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.become(waitingForFirstSubscriber)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForFirstSubscriber: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
      import context.dispatcher
      future.map(FutureValue) pipeTo (self)
      context.become(active)
  }

  def active: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
    case RequestMore(subscription, elements) ⇒ // FIXME we aren't tracking demand per subscription so we don't check for overflow. We should.
      if (subscriptions.contains(subscription)) {
        if (elements < 1) {
          val subscriber = subscriptions(subscription)
          rejectDueToNonPositiveDemand(subscriber)
          removeSubscriber(subscriber)
        } else {
          subscriptionsReadyForPush += subscription
          push(subscriptions(subscription))
        }
      }
    case Cancel(subscription) if subscriptions.contains(subscription) ⇒
      removeSubscriber(subscriptions(subscription))
    case Status.Failure(ex) ⇒
      if (futureValue.isEmpty) {
        futureValue = Some(Failure(ex))
        pushToAll()
      }
    case FutureValue(value) ⇒
      if (futureValue.isEmpty) {
        futureValue = Some(Success(value))
        pushToAll()
      }
  }

  def pushToAll(): Unit = subscriptionsReadyForPush foreach { subscription ⇒ push(subscriptions(subscription)) }

  def push(subscriber: Subscriber[Any]): Unit =
    futureValue match {
      case Some(someValue) ⇒ try someValue match {
        case Success(value) ⇒
          tryOnNext(subscriber, value)
          tryOnComplete(subscriber)
        case Failure(t) ⇒
          shutdownReason = Some(t)
          tryOnError(subscriber, t)
      } catch {
        case _: SpecViolation ⇒ // continue
      } finally {
        removeSubscriber(subscriber)
      }
      case None ⇒ // not completed yet
    }

  def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
    if (subscribers.contains(subscriber))
      rejectDuplicateSubscriber(subscriber)
    else {
      val subscription = new FutureSubscription(self)
      subscribers = subscribers.updated(subscriber, subscription)
      subscriptions = subscriptions.updated(subscription, subscriber)
      tryOnSubscribe(subscriber, subscription)
    }
  }

  def removeSubscriber(subscriber: Subscriber[Any]): Unit = {
    val subscription = subscribers(subscriber)
    subscriptions -= subscription
    subscriptionsReadyForPush -= subscription
    subscribers -= subscriber
    if (subscribers.isEmpty) {
      exposedPublisher.shutdown(shutdownReason)
      context.stop(self)
    }
  }

  override def postStop(): Unit =
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(shutdownReason)

}

