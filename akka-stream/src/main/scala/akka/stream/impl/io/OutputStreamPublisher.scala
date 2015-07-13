/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import akka.actor._
import akka.stream.actor.ActorPublisherMessage
import akka.util.ByteString

import scala.concurrent.Promise

/** INTERNAL API */
private[akka] object OutputStreamPublisher {
  def props(bytesReadPromise: Promise[Long], bufSize: Int) = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(classOf[OutputStreamPublisher], bytesReadPromise, bufSize)
      .withDeploy(Deploy.local)
  }

  sealed trait OutputStreamPublisherInMessage
  case class Write(bs: ByteString) extends OutputStreamPublisherInMessage
  case object Flush extends OutputStreamPublisherInMessage
  case object Close extends OutputStreamPublisherInMessage

  sealed trait OutputStreamPublisherOutMessage
  case object Ok extends OutputStreamPublisherOutMessage
  case object Canceled extends OutputStreamPublisherOutMessage
}

/** INTERNAL API */
private[akka] class OutputStreamPublisher(bytesReadPromise: Promise[Long], bufSize: Int)
  extends akka.stream.actor.ActorPublisher[ByteString]
  with ActorLogging {

  var availableChunks: Vector[ByteString] = Vector.empty[ByteString]
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
      else context.become(sendAcknowledgementThenStop)

    //Upstream messages from OutputStreamAdapter
    case OutputStreamPublisher.Write(bs) ⇒ onWrite(bs)
    case OutputStreamPublisher.Flush     ⇒ flush()
    case OutputStreamPublisher.Close     ⇒ flush(); onCompleteThenStop()
  }

  import OutputStreamPublisher._
  def sendAcknowledgementThenStop: Receive = {
    case Write(_) | Close | Flush ⇒
      sender ! Canceled
      context.stop(self)
  }

  def signalDownstream(): Unit =
    if (isActive) {
      while (totalDemand > 0 && availableChunks.nonEmpty) {
        val ready = availableChunks.head
        availableChunks = availableChunks.tail
        bytesSentDownstream += ready.size
        onNext(ready)
        confirmWriteToUpstream()
      }
      confirmFlushToUpstream()
    }

  def confirmWriteToUpstream(notify: (ActorRef) ⇒ Unit = signalOkUpstream(_)) = {
    notifyWhenSend match {
      case Some(sender) ⇒
        notify(sender)
        if (onCancelReceived) sentCancelledAcknowledgement = true
        notifyWhenSend = None
      case None ⇒ // do nothing
    }
  }

  def confirmFlushToUpstream() = {
    if (availableChunks.isEmpty)
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

  def isBufferFull = availableChunks.size == bufSize

  def onWrite(byteString: ByteString) = {
    if (isActive) {
      availableChunks :+= byteString

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
