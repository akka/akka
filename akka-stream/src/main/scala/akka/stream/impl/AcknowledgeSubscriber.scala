/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ ActorLogging, ActorRef, Props, Status }
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.actor.{ ActorSubscriber, ActorSubscriberMessage, RequestStrategy }

import scala.util.{ Try, Failure, Success }

private[akka] object AcknowledgeSubscriber {
  def props(highWatermark: Int) =
    Props(new AcknowledgeSubscriber(highWatermark))
}

/**
 * INTERNAL API
 */
private[akka] class AcknowledgeSubscriber(maxBuffer: Int) extends ActorSubscriber with ActorLogging {
  import ActorSubscriberMessage._

  var buffer: Vector[Any] = Vector.empty

  override val requestStrategy = new RequestStrategy {
    def requestDemand(remainingRequested: Int): Int = {
      maxBuffer - buffer.size - remainingRequested
    }
  }

  var requester: Option[ActorRef] = None

  def receive = {
    case Request(_) ⇒
      if (requester.isEmpty) {
        requester = Some(sender)
        trySendElementDownstream()
      } else
        sender ! Status.Failure(
          new IllegalStateException("You have to wait for first future to be resolved to send another request"))

    case OnNext(elem) ⇒
      if (maxBuffer != 0) {
        buffer :+= elem
        trySendElementDownstream()
      } else requester match {
        case Some(ref) ⇒
          requester = None
          ref ! Some(elem)
        case None ⇒ log.debug("Dropping element because there is no downstream demand: [{}]", elem)
      }

    case OnError(cause) ⇒
      trySendDownstream(Status.Failure(cause))
      context.stop(self)

    case OnComplete ⇒
      if (buffer.isEmpty) {
        trySendDownstream(Status.Success(None))
        context.stop(self)
      }
  }

  def trySendElementDownstream(): Unit = {
    requester match {
      case Some(ref) ⇒
        if (buffer.size > 0) {
          ref ! Some(buffer.head)
          requester = None
          buffer = buffer.tail
        } else if (canceled) {
          ref ! None
          context.stop(self)
        }

      case None ⇒ //do nothing
    }
  }

  def trySendDownstream(e: Any): Unit = {
    requester match {
      case Some(ref) ⇒
        ref ! e
      case None ⇒ //do nothing
    }
  }
}
