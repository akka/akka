/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal

import akka.actor.{ Actor, ActorRef, Props, SupervisorStrategy, Terminated }
import akka.stream.{ MaterializerSettings, ReactiveStreamsConstants }
import org.reactivestreams.{ Subscriber, Subscription }

/**
 * INTERNAL API
 */
private[akka] object IterablePublisher {
  def props(iterable: immutable.Iterable[Any], settings: MaterializerSettings): Props =
    Props(new IterablePublisher(iterable, settings)).withDispatcher(settings.dispatcher)

  object BasicActorSubscription {
    case object Cancel
    case class RequestMore(elements: Long)
  }

  class BasicActorSubscription(ref: ActorRef)
    extends Subscription {
    import akka.stream.impl.IterablePublisher.BasicActorSubscription._
    def cancel(): Unit = ref ! Cancel
    def request(elements: Long): Unit =
      if (elements <= 0) throw new IllegalArgumentException(ReactiveStreamsConstants.NumberOfElementsInRequestMustBePositiveMsg)
      else ref ! RequestMore(elements)
    override def toString = "BasicActorSubscription"
  }
}

/**
 * INTERNAL API
 *
 * Elements are produced from the iterator of the iterable. Each subscriber
 * makes use of its own iterable, i.e. each subscriber will receive the elements from the
 * beginning of the iterable and it can consume the elements in its own pace.
 */
private[akka] class IterablePublisher(iterable: immutable.Iterable[Any], settings: MaterializerSettings) extends Actor with SoftShutdown {
  import akka.stream.impl.IterablePublisher.BasicActorSubscription

  require(iterable.nonEmpty, "Use EmptyPublisher for empty iterable")

  var exposedPublisher: ActorPublisher[Any] = _
  var subscribers = Set.empty[Subscriber[Any]]
  var workers = Map.empty[ActorRef, Subscriber[Any]]

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
      context.become(active)
  }

  def active: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber

    case Terminated(worker) ⇒
      workerFinished(worker)

    case IterablePublisherWorker.Finished ⇒
      context.unwatch(sender)
      workerFinished(sender)
  }

  private def workerFinished(worker: ActorRef): Unit = {
    val subscriber = workers(worker)
    workers -= worker
    subscribers -= subscriber
    if (subscribers.isEmpty) {
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
      softShutdown()
    }
  }

  def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
    if (subscribers(subscriber))
      subscriber.onError(new IllegalStateException(s"${getClass.getSimpleName} [$self, sub: $subscriber] ${ReactiveStreamsConstants.CanNotSubscribeTheSameSubscriberMultipleTimes}"))
    else {
      val iterator = iterable.iterator
      val worker = context.watch(context.actorOf(IterablePublisherWorker.props(iterator, subscriber,
        settings.maxInputBufferSize).withDispatcher(context.props.dispatcher)))
      val subscription = new BasicActorSubscription(worker)
      subscribers += subscriber
      workers = workers.updated(worker, subscriber)
      subscriber.onSubscribe(subscription)
    }
  }

  override def postStop(): Unit = {
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
  }

}

/**
 * INTERNAL API
 */
private[akka] object IterablePublisherWorker {
  def props(iterator: Iterator[Any], subscriber: Subscriber[Any], maxPush: Int): Props =
    Props(new IterablePublisherWorker(iterator, subscriber, maxPush))

  private object PushMore
  case object Finished
}

/**
 * INTERNAL API
 *
 * Each subscriber is served by this worker actor. It pushes elements to the
 * subscriber immediately when it receives demand, but to allow cancel before
 * pushing everything it sends a PushMore to itself after a batch of elements.
 */
private[akka] class IterablePublisherWorker(iterator: Iterator[Any], subscriber: Subscriber[Any], maxPush: Int)
  extends Actor with SoftShutdown {
  import akka.stream.impl.IterablePublisher.BasicActorSubscription._
  import akka.stream.impl.IterablePublisherWorker._

  require(iterator.hasNext, "Iterator must not be empty")

  var pendingDemand: Long = 0L

  def receive = {
    case RequestMore(elements) ⇒
      pendingDemand += elements
      push()
    case PushMore ⇒
      push()
    case Cancel ⇒
      context.parent ! Finished
      softShutdown()
  }

  private def push(): Unit = {
    @tailrec def doPush(n: Int): Unit =
      if (pendingDemand > 0) {
        pendingDemand -= 1
        val hasNext = {
          subscriber.onNext(iterator.next())
          iterator.hasNext
        }
        if (!hasNext) {
          subscriber.onComplete()
          context.parent ! Finished
          softShutdown()
        } else if (n == 0 && pendingDemand > 0)
          self ! PushMore
        else
          doPush(n - 1)
      }

    try doPush(maxPush) catch {
      case NonFatal(e) ⇒
        subscriber.onError(e)
        context.parent ! Finished
        softShutdown()
    }
  }
}

