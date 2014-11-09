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
import akka.stream.ReactiveStreamsConstants
import akka.pattern.pipe
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * INTERNAL API
 */
private[akka] object FuturePublisher {
  def props(future: Future[Any], settings: MaterializerSettings): Props =
    Props(new FuturePublisher(future, settings)).withDispatcher(settings.dispatcher)

  object FutureSubscription {
    case class Cancel(subscription: FutureSubscription)
    case class RequestMore(subscription: FutureSubscription)
  }

  class FutureSubscription(ref: ActorRef) extends Subscription {
    import akka.stream.impl.FuturePublisher.FutureSubscription._
    def cancel(): Unit = ref ! Cancel(this)
    def request(elements: Long): Unit =
      if (elements <= 0) throw new IllegalArgumentException(ReactiveStreamsConstants.NumberOfElementsInRequestMustBePositiveMsg)
      else ref ! RequestMore(this)
    override def toString = "FutureSubscription"
  }
}

/**
 * INTERNAL API
 */
//FIXME why do we need to have an actor to drive a Future?
private[akka] class FuturePublisher(future: Future[Any], settings: MaterializerSettings) extends Actor with SoftShutdown {
  import akka.stream.impl.FuturePublisher.FutureSubscription
  import akka.stream.impl.FuturePublisher.FutureSubscription.Cancel
  import akka.stream.impl.FuturePublisher.FutureSubscription.RequestMore

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
      subscriber.onError(new IllegalStateException(s"${getClass.getSimpleName} [$self, sub: $subscriber] ${ReactiveStreamsConstants.CanNotSubscribeTheSameSubscriberMultipleTimes}"))
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

