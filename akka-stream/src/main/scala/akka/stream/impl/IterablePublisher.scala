/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Actor, ActorRef, Props, SupervisorStrategy, Terminated }
import akka.stream.MaterializerSettings
import org.reactivestreams.{ Subscriber, Subscription }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object IterablePublisher {
  def props(iterable: immutable.Iterable[Any], settings: MaterializerSettings): Props =
    Props(new IterablePublisher(iterable, settings)).withDispatcher(settings.dispatcher)

  object BasicActorSubscription {
    case object Cancel
    case class RequestMore(elements: Int)
  }

  class BasicActorSubscription(ref: ActorRef)
    extends Subscription {
    import akka.stream.impl.IterablePublisher.BasicActorSubscription._
    def cancel(): Unit = ref ! Cancel
    def request(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
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
  import akka.stream.impl.ActorBasedFlowMaterializer._
  import akka.stream.impl.IterablePublisher.BasicActorSubscription

  require(iterable.nonEmpty, "Use EmptyPublisher for empty iterable")

  var exposedPublisher: ActorPublisher[Any] = _
  var subscribers = Set.empty[Subscriber[Any]]
  var workers = Map.empty[ActorRef, Subscriber[Any]]

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
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    else {
      val iterator = withCtx(context)(iterable.iterator)
      val worker = context.watch(context.actorOf(IterablePublisherWorker.props(iterator, subscriber,
        settings.maximumInputBufferSize).withDispatcher(context.props.dispatcher)))
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
  import akka.stream.impl.ActorBasedFlowMaterializer._
  import akka.stream.impl.IterablePublisher.BasicActorSubscription._
  import akka.stream.impl.IterablePublisherWorker._

  require(iterator.hasNext, "Iterator must not be empty")

  var demand = 0L

  def receive = {
    case RequestMore(elements) ⇒
      demand += elements
      push()
    case PushMore ⇒
      push()
    case Cancel ⇒
      context.parent ! Finished
      softShutdown()
  }

  private def push(): Unit = {
    @tailrec def doPush(n: Int): Unit =
      if (demand > 0) {
        demand -= 1
        val hasNext = withCtx(context) {
          subscriber.onNext(iterator.next())
          iterator.hasNext
        }
        if (!hasNext) {
          subscriber.onComplete()
          context.parent ! Finished
          softShutdown()
        } else if (n == 0 && demand > 0)
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

