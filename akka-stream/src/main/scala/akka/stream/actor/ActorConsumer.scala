/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.actor

import java.util.concurrent.ConcurrentHashMap
import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.Subscriber
import org.reactivestreams.spi.Subscription
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

/**
 * Use this class to attach a [[ActorConsumerDestination]] as a [[org.reactivestreams.Consumer]]
 * to a [[org.reactivestreams.Producer]] or [[akka.stream.Flow]].
 */
case class ActorConsumer[T](ref: ActorRef) extends Consumer[Any] {
  override val getSubscriber: Subscriber[Any] = new ActorSubscriber[Any](ref)
}

/**
 * INTERNAL API
 */
private[akka] final class ActorSubscriber[T](val impl: ActorRef) extends Subscriber[T] {
  import ActorConsumerDestination._
  override def onError(cause: Throwable): Unit = impl ! OnError(cause)
  override def onComplete(): Unit = impl ! OnComplete
  override def onNext(element: T): Unit = impl ! OnNext(element)
  override def onSubscribe(subscription: Subscription): Unit = impl ! OnSubscribe(subscription)
}

object ActorConsumerDestination {

  case class OnNext(element: Any)
  case object OnComplete
  case class OnError(cause: Throwable)

  /**
   * INTERNAL API
   */
  private[akka] case class OnSubscribe(subscription: Subscription)
}

/**
 * Extend/mixin this trait in your [[akka.actor.Actor]] to make it a
 * stream consumer with full control of stream backpressure. It will receive
 * [[ActorConsumerDestination.OnNext]], [[ActorConsumerDestination.OnComplete]] and
 * [[ActorConsumerDestination.OnError]] messages from the stream.
 *
 * Attach the actor as a [[org.reactivestreams.Consumer]] to the stream with
 * [[ActorConsumer]].
 *
 * Subclass must stream control backpressure by calling [[#request]] to receive elements
 * from upstream. This must also be done when the actor is started, otherwise it will
 * not receive any elements.
 *
 * Use [[#remainingRequested]] to see how many remaining number of elements
 * have been requested from upstream but not received yet is
 */
trait ActorConsumerDestination extends Actor { // FIXME name?
  import ActorConsumerDestination._

  private val state = ActorConsumerState(context.system)
  private var (subscription: Option[Subscription], requested: Int) = state.get(self) match {
    case Some(s) ⇒ (s.subscription, s.requested)
    case None    ⇒ (None, 0)
  }

  protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case OnNext(_) ⇒
      requested -= 1
      super.aroundReceive(receive, msg)
    case OnSubscribe(sub) if subscription.isEmpty ⇒
      subscription = Some(sub)
      if (requested != 0)
        sub.requestMore(requested)
    case OnSubscribe(sub) ⇒
      sub.cancel()
    case _ ⇒
      super.aroundReceive(receive, msg)
  }

  protected[akka] override def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    // some state must survive restart
    state.set(self, ActorConsumerState.State(subscription, requested))
    super.aroundPreRestart(reason, message)
  }

  protected[akka] override def aroundPostStop(): Unit = {
    subscription.foreach(_.cancel())
    super.aroundPostStop()
  }

  /**
   * Remaining number of elements that have been requested from
   * upstream but not received yet.
   */
  def remainingRequested: Int = requested

  /**
   * Request a number of elements from upstream.
   */
  protected def request(elements: Int): Unit = {
    // if we don't have a subscription yet, it will be requested when it arrives
    subscription.foreach(_.requestMore(elements))
    requested += elements
  }

  // FIXME implement cancel (must also be in state)

}

/**
 * INTERNAL API
 * Some state must survive restarts.
 */
private[akka] object ActorConsumerState extends ExtensionId[ActorConsumerState] with ExtensionIdProvider {
  override def get(system: ActorSystem): ActorConsumerState = super.get(system)

  override def lookup = ActorConsumerState

  override def createExtension(system: ExtendedActorSystem): ActorConsumerState =
    new ActorConsumerState

  case class State(subscription: Option[Subscription], requested: Int)

}

/**
 * INTERNAL API
 */
private[akka] class ActorConsumerState extends Extension {
  import ActorConsumerState.State
  private val state = new ConcurrentHashMap[ActorRef, State]

  def get(ref: ActorRef): Option[State] = Option(state.get(ref))

  def set(ref: ActorRef, s: State): Unit = state.put(ref, s)

  def remove(ref: ActorRef): Unit = state.remove(ref)
}