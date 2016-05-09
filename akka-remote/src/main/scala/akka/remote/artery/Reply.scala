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
private[akka] object ReplyJunction {

  private[akka] trait ReplySubject {
    def attach(observer: ReplyObserver): Future[Done]
    def detach(observer: ReplyObserver): Unit
    def stopped: Future[Done]
  }

  private[akka] trait ReplyObserver {
    def reply(inboundEnvelope: InboundEnvelope): Unit
  }
}

/**
 * INTERNAL API
 */
private[akka] class ReplyJunction
  extends GraphStageWithMaterializedValue[FlowShape[InboundEnvelope, InboundEnvelope], ReplyJunction.ReplySubject] {
  import ReplyJunction._

  val in: Inlet[InboundEnvelope] = Inlet("ReplyJunction.in")
  val out: Outlet[InboundEnvelope] = Outlet("ReplyJunction.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val logic = new GraphStageLogic(shape) with InHandler with OutHandler with ReplySubject {

      private var replyObservers: Vector[ReplyObserver] = Vector.empty
      private val stoppedPromise = Promise[Done]()

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

      override def attach(observer: ReplyObserver): Future[Done] = {
        val p = Promise[Done]()
        getAsyncCallback[Unit](_ ⇒ {
          replyObservers :+= observer
          p.success(Done)
        }).invoke(())
        p.future
      }

      override def detach(observer: ReplyObserver): Unit = {
        replyObservers = replyObservers.filterNot(_ == observer)
      }

      override def stopped: Future[Done] = stoppedPromise.future

      setHandlers(in, out, this)
    }
    (logic, logic)
  }
}
