/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Actor, Props, Status, SupervisorStrategy }
import akka.stream.MaterializerSettings
import org.reactivestreams.Subscriber

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
private[akka] object FuturePublisher {
  def props(future: Future[Any], settings: MaterializerSettings): Props =
    Props(new FuturePublisher(future, settings)).withDispatcher(settings.dispatcher)
}

/**
 * INTERNAL API
 */
private[akka] class FuturePublisher(future: Future[Any], settings: MaterializerSettings) extends Actor with SoftShutdown {

  var exposedPublisher: LazyPublisherLike[Any] = _
  var subscribers = Map.empty[Subscriber[Any], LazySubscription[Any]]
  var subscriptions = Map.empty[LazySubscription[Any], Subscriber[Any]]
  var subscriptionsReadyForPush = Set.empty[LazySubscription[Any]]
  var futureValue: Option[Try[Any]] = future.value
  var shutdownReason = LazyActorPublisher.NormalShutdownReason

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      val subscribers = exposedPublisher.takeEarlySubscribers(self)

      subscribers foreach {
        case (subscription, demand) ⇒
          registerSubscriber(subscription.subscriber, subscription)
          if (demand > 0) {
            self ! RequestMore(subscription, demand)
          }
      }

      implicit val ec = context.dispatcher
      future.onComplete(res ⇒ self ! res)

      context.become(active)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def active: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach { sub ⇒ registerSubscriber(sub) }
    case RequestMore(subscription, demand) ⇒
      if (subscriptions.contains(subscription)) {
        subscriptionsReadyForPush += subscription
        push(subscriptions(subscription))
      }
    case Cancel(subscription) if subscriptions.contains(subscription) ⇒
      removeSubscriber(subscriptions(subscription))
    case Status.Failure(ex) ⇒
      futureValue = Some(Failure(ex))
      pushToAll()
    case Success(value) ⇒
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
    val subscription = new LazySubscription(exposedPublisher, subscriber)
    registerSubscriber(subscriber, subscription)
    subscriber.onSubscribe(subscription)
  }

  /**
   * Register an existing subscriber
   */
  protected def registerSubscriber(subscriber: Subscriber[Any], existingSubscription: LazySubscription[Any]): Unit = {
    if (subscribers.contains(subscriber))
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    else {
      subscribers = subscribers.updated(subscriber, existingSubscription)
      subscriptions = subscriptions.updated(existingSubscription, subscriber)
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

