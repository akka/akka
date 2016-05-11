/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.Future
import scala.concurrent.Promise
import akka.Done
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.remote.EndpointManager.Send
import java.util.ArrayDeque
import akka.stream.stage.CallbackWrapper

/**
 * Marker trait for reply messages
 */
trait Reply extends ControlMessage

/**
 * Marker trait for control messages that can be sent via the system message sub-channel
 * but don't need full reliable delivery. E.g. `HandshakeReq` and `Reply`.
 */
trait ControlMessage

/**
 * INTERNAL API
 */
private[akka] object InboundReplyJunction {

  // FIXME rename all Reply stuff to Control or ControlMessage

  private[akka] trait ReplySubject {
    def attach(observer: ReplyObserver): Future[Done]
    def detach(observer: ReplyObserver): Unit
    def stopped: Future[Done]
  }

  private[akka] trait ReplyObserver {
    def reply(inboundEnvelope: InboundEnvelope): Unit
  }

  private[InboundReplyJunction] sealed trait CallbackMessage
  private[InboundReplyJunction] final case class Attach(observer: ReplyObserver, done: Promise[Done])
    extends CallbackMessage
  private[InboundReplyJunction] final case class Dettach(observer: ReplyObserver) extends CallbackMessage
}

/**
 * INTERNAL API
 */
private[akka] class InboundReplyJunction
  extends GraphStageWithMaterializedValue[FlowShape[InboundEnvelope, InboundEnvelope], InboundReplyJunction.ReplySubject] {
  import InboundReplyJunction._

  val in: Inlet[InboundEnvelope] = Inlet("InboundReplyJunction.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundReplyJunction.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stoppedPromise = Promise[Done]()
    // FIXME see issue #20503 related to CallbackWrapper, we might implement this in a better way
    val logic = new GraphStageLogic(shape) with CallbackWrapper[CallbackMessage] with InHandler with OutHandler {

      private var replyObservers: Vector[ReplyObserver] = Vector.empty

      private val callback = getAsyncCallback[CallbackMessage] {
        case Attach(observer, done) ⇒
          replyObservers :+= observer
          done.success(Done)
        case Dettach(observer) ⇒
          replyObservers = replyObservers.filterNot(_ == observer)
      }

      override def preStart(): Unit = {
        initCallback(callback.invoke)
      }

      override def postStop(): Unit = stoppedPromise.success(Done)

      // InHandler
      override def onPush(): Unit = {
        grab(in) match {
          case env @ InboundEnvelope(_, _, reply: Reply, _) ⇒
            replyObservers.foreach(_.reply(env))
            pull(in)
          case env ⇒
            push(out, env)
        }
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }

    // materialized value
    val replySubject: ReplySubject = new ReplySubject {
      override def attach(observer: ReplyObserver): Future[Done] = {
        val p = Promise[Done]()
        logic.invoke(Attach(observer, p))
        p.future
      }

      override def detach(observer: ReplyObserver): Unit =
        logic.invoke(Dettach(observer))

      override def stopped: Future[Done] =
        stoppedPromise.future
    }

    (logic, replySubject)
  }
}

/**
 * INTERNAL API
 */
private[akka] object OutboundReplyJunction {
  trait OutboundReplyIngress {
    def sendControlMessage(message: ControlMessage): Unit
  }
}

/**
 * INTERNAL API
 */
private[akka] class OutboundReplyJunction(outboundContext: OutboundContext)
  extends GraphStageWithMaterializedValue[FlowShape[Send, Send], OutboundReplyJunction.OutboundReplyIngress] {
  import OutboundReplyJunction._
  val in: Inlet[Send] = Inlet("OutboundReplyJunction.in")
  val out: Outlet[Send] = Outlet("OutboundReplyJunction.out")
  override val shape: FlowShape[Send, Send] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    // FIXME see issue #20503 related to CallbackWrapper, we might implement this in a better way
    val logic = new GraphStageLogic(shape) with CallbackWrapper[ControlMessage] with InHandler with OutHandler {
      import OutboundReplyJunction._

      private val sendControlMessageCallback = getAsyncCallback[ControlMessage](internalSendControlMessage)
      private val buffer = new ArrayDeque[Send]

      override def preStart(): Unit = {
        initCallback(sendControlMessageCallback.invoke)
      }

      // InHandler
      override def onPush(): Unit = {
        if (buffer.isEmpty && isAvailable(out))
          push(out, grab(in))
        else
          buffer.offer(grab(in))
      }

      // OutHandler
      override def onPull(): Unit = {
        if (buffer.isEmpty && !hasBeenPulled(in))
          pull(in)
        else if (!buffer.isEmpty)
          push(out, buffer.poll())
      }

      private def internalSendControlMessage(message: ControlMessage): Unit = {
        if (buffer.isEmpty && isAvailable(out))
          push(out, wrap(message))
        else
          buffer.offer(wrap(message))
      }

      private def wrap(message: ControlMessage): Send =
        Send(message, None, outboundContext.dummyRecipient, None)

      setHandlers(in, out, this)
    }

    // materialized value
    val outboundReplyIngress = new OutboundReplyIngress {
      override def sendControlMessage(message: ControlMessage): Unit =
        logic.invoke(message)
    }

    (logic, outboundReplyIngress)
  }

}
