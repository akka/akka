/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Status
import akka.actor.SupervisorStrategy
import akka.stream.MaterializerSettings
import akka.pattern.pipe
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import akka.actor.DeadLetterSuppression

/**
 * INTERNAL API
 */
private[akka] object FuturePublisher {
  def props(future: Future[Any], settings: MaterializerSettings): Props =
    Props(new FuturePublisher(future, settings)).withDispatcher(settings.dispatcher)

  object FutureSubscription {
    final case class Cancel(subscription: FutureSubscription) extends DeadLetterSuppression
    final case class RequestMore(subscription: FutureSubscription, elements: Long) extends DeadLetterSuppression
  }

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
private[akka] class FuturePublisher(future: Future[Any], settings: MaterializerSettings) extends Actor with SoftShutdown {
  import akka.stream.impl.FuturePublisher.FutureSubscription
  import akka.stream.impl.FuturePublisher.FutureSubscription.Cancel
  import akka.stream.impl.FuturePublisher.FutureSubscription.RequestMore
  import ReactiveStreamsCompliance._

  var exposedPublisher: ActorPublisher[Any] = _
  var subscribers = Map.empty[Subscriber[Any], FutureSubscription]
  var subscriptions = Map.empty[FutureSubscription, Subscriber[Any]]
  var subscriptionsReadyForPush = Set.empty[FutureSubscription]
  var futureValue: Option[Try[Any]] = future.value
  var shutdownReason = ActorPublisher.NormalShutdownReason

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
      future.pipeTo(self)
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
    case value ⇒
      if (futureValue.isEmpty) {
        futureValue = Some(Success(value))
        pushToAll()
      }
  }

  def pushToAll(): Unit = subscriptionsReadyForPush foreach { subscription ⇒ push(subscriptions(subscription)) }

  def push(subscriber: Subscriber[Any]): Unit = futureValue match {
    case Some(Success(value)) ⇒

      tryOnNext(subscriber, value)
      tryOnComplete(subscriber)
      removeSubscriber(subscriber)
    case Some(Failure(t)) ⇒
      tryOnError(subscriber, t)
      removeSubscriber(subscriber)
    case None ⇒ // not completed yet
  }

  def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
    if (subscribers.contains(subscriber)) // FIXME this is not legal AFAICT, needs to check identity, not equality
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
      softShutdown()
    }
  }

  override def postStop(): Unit = // FIXME if something blows up, are the subscribers onErrored?
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(shutdownReason)

}

