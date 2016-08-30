/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import akka.Done
import akka.actor.Address
import akka.remote.transport.ThrottlerTransportAdapter.Blackhole
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.remote.transport.ThrottlerTransportAdapter.SetThrottle
import akka.remote.transport.ThrottlerTransportAdapter.Unthrottled
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage.AsyncCallback
import akka.stream.stage.CallbackWrapper
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogic
import akka.util.OptionVal
import akka.stream.stage.GraphStageLogic

/**
 * INTERNAL API
 */
private[remote] trait TestManagementApi {
  def send(command: Any)(implicit ec: ExecutionContext): Future[Done]
}

/**
 * INTERNAL API
 */
private[remote] class TestManagementApiImpl(stopped: Future[Done], callback: AsyncCallback[TestManagementMessage])
  extends TestManagementApi {

  override def send(command: Any)(implicit ec: ExecutionContext): Future[Done] = {
    if (stopped.isCompleted)
      Future.successful(Done)
    else {
      val done = Promise[Done]()
      callback.invoke(TestManagementMessage(command, done))
      Future.firstCompletedOf(List(done.future, stopped))
    }
  }
}

private[remote] class DisabledTestManagementApi extends TestManagementApi {
  override def send(command: Any)(implicit ec: ExecutionContext): Future[Done] =
    Future.failed(new RuntimeException("TestStage is disabled, enable with MultiNodeConfig.testTransport"))
}

/**
 * INTERNAL API
 */
private[remote] final case class TestManagementMessage(command: Any, done: Promise[Done])

/**
 * INTERNAL API
 */
private[remote] class OutboundTestStage(outboundContext: OutboundContext, enabled: Boolean)
  extends GraphStageWithMaterializedValue[FlowShape[OutboundEnvelope, OutboundEnvelope], TestManagementApi] {
  val in: Inlet[OutboundEnvelope] = Inlet("OutboundTestStage.in")
  val out: Outlet[OutboundEnvelope] = Outlet("OutboundTestStage.out")
  override val shape: FlowShape[OutboundEnvelope, OutboundEnvelope] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    if (enabled) {
      val stoppedPromise = Promise[Done]()

      // FIXME see issue #20503 related to CallbackWrapper, we might implement this in a better way
      val logic = new TimerGraphStageLogic(shape) with CallbackWrapper[TestManagementMessage] with InHandler with OutHandler with StageLogging {

        private var blackhole = Set.empty[Address]

        private val callback = getAsyncCallback[TestManagementMessage] {
          case TestManagementMessage(command, done) ⇒
            command match {
              case SetThrottle(address, Direction.Send | Direction.Both, Blackhole) ⇒
                log.info("blackhole outbound messages to {}", address)
                blackhole += address
              case SetThrottle(address, Direction.Send | Direction.Both, Unthrottled) ⇒
                log.info("accept outbound messages to {}", address)
                blackhole -= address
              case _ ⇒ // not interested
            }
            done.success(Done)
        }

        override def preStart(): Unit = {
          initCallback(callback.invoke)
        }

        override def postStop(): Unit = stoppedPromise.success(Done)

        // InHandler
        override def onPush(): Unit = {
          val env = grab(in)
          if (blackhole(outboundContext.remoteAddress)) {
            log.debug(
              "dropping outbound message [{}] to [{}] because of blackhole",
              env.message.getClass.getName, outboundContext.remoteAddress)
            pull(in) // drop message
          } else
            push(out, env)
        }

        // OutHandler
        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }

      val managementApi: TestManagementApi = new TestManagementApiImpl(stoppedPromise.future, logic)

      (logic, managementApi)
    } else {
      val logic = new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onPull(): Unit = pull(in)
        setHandlers(in, out, this)
      }
      (logic, new DisabledTestManagementApi)
    }
  }

}

/**
 * INTERNAL API
 */
private[remote] class InboundTestStage(inboundContext: InboundContext, enabled: Boolean)
  extends GraphStageWithMaterializedValue[FlowShape[InboundEnvelope, InboundEnvelope], TestManagementApi] {
  val in: Inlet[InboundEnvelope] = Inlet("InboundTestStage.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundTestStage.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    if (enabled) {
      val stoppedPromise = Promise[Done]()

      // FIXME see issue #20503 related to CallbackWrapper, we might implement this in a better way
      val logic = new TimerGraphStageLogic(shape) with CallbackWrapper[TestManagementMessage] with InHandler with OutHandler with StageLogging {

        private var blackhole = Set.empty[Address]

        private val callback = getAsyncCallback[TestManagementMessage] {
          case TestManagementMessage(command, done) ⇒
            command match {
              case SetThrottle(address, Direction.Receive | Direction.Both, Blackhole) ⇒
                log.info("blackhole inbound messages from {}", address)
                blackhole += address
              case SetThrottle(address, Direction.Receive | Direction.Both, Unthrottled) ⇒
                log.info("accept inbound messages from {}", address)
                blackhole -= address
              case _ ⇒ // not interested
            }
            done.success(Done)
        }

        override def preStart(): Unit = {
          initCallback(callback.invoke)
        }

        override def postStop(): Unit = stoppedPromise.success(Done)

        // InHandler
        override def onPush(): Unit = {
          val env = grab(in)
          env.association match {
            case OptionVal.None ⇒
              // unknown, handshake not completed
              push(out, env)
            case OptionVal.Some(association) ⇒
              if (blackhole(association.remoteAddress)) {
                log.debug(
                  "dropping inbound message [{}] from [{}] with UID [{}] because of blackhole",
                  env.message.getClass.getName, association.remoteAddress, env.originUid)
                pull(in) // drop message
              } else
                push(out, env)
          }
        }

        // OutHandler
        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }

      val managementApi: TestManagementApi = new TestManagementApiImpl(stoppedPromise.future, logic)

      (logic, managementApi)
    } else {
      val logic = new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onPull(): Unit = pull(in)
        setHandlers(in, out, this)
      }
      (logic, new DisabledTestManagementApi)
    }
  }

}

