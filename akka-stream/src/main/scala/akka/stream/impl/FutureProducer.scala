/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import org.reactivestreams.spi.Subscriber
import org.reactivestreams.spi.Subscription
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Status
import akka.actor.SupervisorStrategy
import akka.pattern.pipe
import akka.stream.MaterializerSettings

/**
 * INTERNAL API
 */
private[akka] object FutureProducer {
  def props(future: Future[Any], settings: MaterializerSettings): Props =
    Props(new FutureProducer(future, settings))

  object FutureSubscription {
    case class Cancel(subscription: FutureSubscription)
    case class RequestMore(subscription: FutureSubscription)
  }

  class FutureSubscription(ref: ActorRef) extends Subscription {
    import FutureSubscription._
    def cancel(): Unit = ref ! Cancel(this)
    def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else ref ! RequestMore(this)
    override def toString = "FutureSubscription"
  }
}

/**
 * INTERNAL API
 */
private[akka] class FutureProducer(future: Future[Any], settings: MaterializerSettings) extends Actor with SoftShutdown {
  import FutureProducer.FutureSubscription
  import FutureProducer.FutureSubscription.Cancel
  import FutureProducer.FutureSubscription.RequestMore

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
      context.setReceiveTimeout(settings.downstreamSubscriptionTimeout)
      context.become(waitingForFirstSubscriber)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForFirstSubscriber: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
      context.setReceiveTimeout(Duration.Undefined)
      import context.dispatcher
      future.pipeTo(self)
      context.become(active)
  }

  def active: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
    case RequestMore(subscription) ⇒
      if (subscriptions.contains(subscription)) {
        subscriptionsReadyForPush += subscription
        push(subscriptions(subscription))
      }
    case Cancel(subscription) if subscriptions.contains(subscription) ⇒
      removeSubscriber(subscriptions(subscription))
    case Status.Failure(ex) ⇒
      futureValue = Some(Failure(ex))
      pushToAll()
    case value ⇒
      futureValue = Some(Success(value))
      pushToAll()
  }

  def pushToAll(): Unit = subscriptionsReadyForPush foreach { subscription ⇒ push(subscriptions(subscription)) }

  def push(subscriber: Subscriber[Any]): Unit = futureValue match {
    case Some(Success(value)) ⇒
      subscriber.onNext(value)
      subscriber.onComplete()
      removeSubscriber(subscriber)
    case Some(Failure(t)) ⇒
      subscriber.onError(t)
      removeSubscriber(subscriber)
    case None ⇒ // not completed yet
  }

  def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
    if (subscribers.contains(subscriber))
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    else {
      val subscription = new FutureSubscription(self)
      subscribers = subscribers.updated(subscriber, subscription)
      subscriptions = subscriptions.updated(subscription, subscriber)
      subscriber.onSubscribe(subscription)
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

  override def postStop(): Unit =
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(shutdownReason)

}

