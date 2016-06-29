/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.ArrayDeque
import scala.concurrent.Future
import scala.concurrent.Promise
import akka.Done
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage.CallbackWrapper
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.remote.UniqueAddress
import akka.util.OptionVal

/**
 * INTERNAL API: Marker trait for reply messages
 */
private[akka] trait Reply extends ControlMessage

/**
 * INTERNAL API
 * Marker trait for control messages that can be sent via the system message sub-channel
 * but don't need full reliable delivery. E.g. `HandshakeReq` and `Reply`.
 */
private[akka] trait ControlMessage

/**
 * INTERNAL API
 */
private[akka] final case class Quarantined(from: UniqueAddress, to: UniqueAddress) extends ControlMessage // FIXME serialization

/**
 * INTERNAL API
 */
private[akka] case class ActorSystemTerminating(from: UniqueAddress) extends ControlMessage // FIXME serialization

/**
 * INTERNAL API
 */
private[akka] case class ActorSystemTerminatingAck(from: UniqueAddress) // FIXME serialization

/**
 * INTERNAL API
 */
private[akka] object InboundControlJunction {

  /**
   * Observer subject for inbound control messages.
   * Interested observers can attach themselves to the
   * subject to get notification of incoming control
   * messages.
   */
  private[akka] trait ControlMessageSubject {
    def attach(observer: ControlMessageObserver): Future[Done]
    def detach(observer: ControlMessageObserver): Unit
    def stopped: Future[Done]
  }

  private[akka] trait ControlMessageObserver {

    /**
     * Notification of incoming control message. The message
     * of the envelope is always a `ControlMessage`.
     */
    def notify(inboundEnvelope: InboundEnvelope): Unit
  }

  // messages for the CallbackWrapper
  private[InboundControlJunction] sealed trait CallbackMessage
  private[InboundControlJunction] final case class Attach(observer: ControlMessageObserver, done: Promise[Done])
    extends CallbackMessage
  private[InboundControlJunction] final case class Dettach(observer: ControlMessageObserver) extends CallbackMessage
}

/**
 * INTERNAL API
 */
private[akka] class InboundControlJunction
  extends GraphStageWithMaterializedValue[FlowShape[InboundEnvelope, InboundEnvelope], InboundControlJunction.ControlMessageSubject] {
  import InboundControlJunction._

  val in: Inlet[InboundEnvelope] = Inlet("InboundControlJunction.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundControlJunction.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stoppedPromise = Promise[Done]()
    // FIXME see issue #20503 related to CallbackWrapper, we might implement this in a better way
    val logic = new GraphStageLogic(shape) with CallbackWrapper[CallbackMessage] with InHandler with OutHandler {

      private var observers: Vector[ControlMessageObserver] = Vector.empty

      private val callback = getAsyncCallback[CallbackMessage] {
        case Attach(observer, done) ⇒
          observers :+= observer
          done.success(Done)
        case Dettach(observer) ⇒
          observers = observers.filterNot(_ == observer)
      }

      override def preStart(): Unit = {
        initCallback(callback.invoke)
      }

      override def postStop(): Unit = stoppedPromise.success(Done)

      // InHandler
      override def onPush(): Unit = {
        grab(in) match {
          case env: InboundEnvelope if env.message.isInstanceOf[ControlMessage] ⇒
            observers.foreach(_.notify(env))
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
    val controlSubject: ControlMessageSubject = new ControlMessageSubject {
      override def attach(observer: ControlMessageObserver): Future[Done] = {
        val p = Promise[Done]()
        logic.invoke(Attach(observer, p))
        p.future
      }

      override def detach(observer: ControlMessageObserver): Unit =
        logic.invoke(Dettach(observer))

      override def stopped: Future[Done] =
        stoppedPromise.future
    }

    (logic, controlSubject)
  }
}

/**
 * INTERNAL API
 */
private[akka] object OutboundControlJunction {
  private[akka] trait OutboundControlIngress {
    def sendControlMessage(message: ControlMessage): Unit
  }
}

/**
 * INTERNAL API
 */
private[akka] class OutboundControlJunction(
  outboundContext: OutboundContext, outboundEnvelopePool: ObjectPool[ReusableOutboundEnvelope])
  extends GraphStageWithMaterializedValue[FlowShape[OutboundEnvelope, OutboundEnvelope], OutboundControlJunction.OutboundControlIngress] {
  import OutboundControlJunction._
  val in: Inlet[OutboundEnvelope] = Inlet("OutboundControlJunction.in")
  val out: Outlet[OutboundEnvelope] = Outlet("OutboundControlJunction.out")
  override val shape: FlowShape[OutboundEnvelope, OutboundEnvelope] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    // FIXME see issue #20503 related to CallbackWrapper, we might implement this in a better way
    val logic = new GraphStageLogic(shape) with CallbackWrapper[ControlMessage] with InHandler with OutHandler with StageLogging {
      import OutboundControlJunction._

      private val sendControlMessageCallback = getAsyncCallback[ControlMessage](internalSendControlMessage)
      private val maxControlMessageBufferSize: Int = 1024 // FIXME config
      private val buffer = new ArrayDeque[OutboundEnvelope]

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
        else if (buffer.size < maxControlMessageBufferSize)
          buffer.offer(wrap(message))
        else {
          // it's alright to drop control messages
          log.debug("Dropping control message [{}] due to full buffer.", message.getClass.getName)
        }
      }

      private def wrap(message: ControlMessage): OutboundEnvelope =
        outboundEnvelopePool.acquire().init(
          recipient = OptionVal.None, message = message, sender = OptionVal.None)

      setHandlers(in, out, this)
    }

    // materialized value
    val outboundControlIngress = new OutboundControlIngress {
      override def sendControlMessage(message: ControlMessage): Unit =
        logic.invoke(message)
    }

    (logic, outboundControlIngress)
  }

}
