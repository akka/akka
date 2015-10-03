/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.util.concurrent.BlockingQueue

import akka.actor._
import akka.stream.actor.ActorPublisherMessage
import akka.util.ByteString

import scala.concurrent.Promise

/** INTERNAL API */
private[akka] object OutputStreamPublisher {
  def props(sharedBuffer: BlockingQueue[ByteString], bytesReadPromise: Promise[Long]) = {
    Props(classOf[OutputStreamPublisher], sharedBuffer, bytesReadPromise)
      .withDeploy(Deploy.local)
  }

  sealed trait OutputStreamPublisherInMessage
  case object WriteNotification extends OutputStreamPublisherInMessage
  case object Flush extends OutputStreamPublisherInMessage
  case object Close extends OutputStreamPublisherInMessage

  sealed trait OutputStreamPublisherOutMessage
  case object Ok extends OutputStreamPublisherOutMessage
  case object Canceled extends OutputStreamPublisherOutMessage
}

/** INTERNAL API */
private[akka] class OutputStreamPublisher(sharedBuffer: BlockingQueue[ByteString],
                                          bytesReadPromise: Promise[Long])
  extends akka.stream.actor.ActorPublisher[ByteString]
  with ActorLogging {

  var bytesSentDownstream = 0L

  //upstream write acknowledgement
  var notifyWhenSend: Option[ActorRef] = None
  //upstream flush acknowledgement
  var notifyWhenSendAll: Option[ActorRef] = None

  var sentCancelledAcknowledgement = false
  var onCancelReceived = false

  def receive = {
    //Downstream messages
    case ActorPublisherMessage.Request(elements) ⇒
      signalDownstream()

    case ActorPublisherMessage.Cancel ⇒
      onCancelReceived = true
      confirmWriteToUpstream(signalCancellationToUpstream)
      if (sentCancelledAcknowledgement) context.stop(self)
      else {
        sharedBuffer.poll() //poll in case buffer is full and OutputStream is blocked
        context.become(sendAcknowledgementThenStop)
      }

    //Upstream messages from OutputStreamAdapter
    case OutputStreamPublisher.WriteNotification ⇒ onWrite()
    case OutputStreamPublisher.Flush             ⇒ flush()
    case OutputStreamPublisher.Close             ⇒ flush(); onCompleteThenStop()
  }

  import OutputStreamPublisher._
  def sendAcknowledgementThenStop: Receive = {
    case WriteNotification | Close | Flush ⇒
      sender ! Canceled
      context.stop(self)
  }

  def signalDownstream(): Unit =
    if (isActive) {
      while (totalDemand > 0 && !sharedBuffer.isEmpty) {
        val ready = sharedBuffer.poll()
        bytesSentDownstream += ready.size
        onNext(ready)
        confirmWriteToUpstream()
      }
      confirmFlushToUpstream()
    }

  def confirmWriteToUpstream(notify: (ActorRef) ⇒ Unit = signalOkUpstream) = {
    notifyWhenSend match {
      case Some(sender) ⇒
        notify(sender)
        if (onCancelReceived) sentCancelledAcknowledgement = true
        notifyWhenSend = None
      case None ⇒ // do nothing
    }
  }

  def confirmFlushToUpstream() = {
    if (sharedBuffer.isEmpty)
      notifyWhenSendAll match {
        case Some(sender) ⇒
          signalOkUpstream(sender)
          notifyWhenSendAll = None
        case None ⇒ //do nothing
      }
  }

  // Considering OutputStreamAdapter as upstream
  def signalOkUpstream(sender: ActorRef) = sender ! OutputStreamPublisher.Ok

  def signalCancellationToUpstream(actor: ActorRef) = actor ! OutputStreamPublisher.Canceled

  override def postStop(): Unit = {
    super.postStop()
    bytesReadPromise.trySuccess(bytesSentDownstream)
  }

  def isBufferFull = sharedBuffer.remainingCapacity() == 0

  def onWrite() = {
    if (isActive) {
      if (isBufferFull)
        notifyWhenSend = Some(sender)
      else
        sender ! OutputStreamPublisher.Ok

      signalDownstream()
    } else {
      signalCancellationToUpstream(sender)
    }
  }

  def flush() = {
    notifyWhenSendAll = Some(sender)
    signalDownstream()
  }

}
