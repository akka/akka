/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.driver

import java.net.InetSocketAddress

import akka.NotUsed
import akka.remote.artery.driver.UdpDriver.Registration
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.scaladsl.Flow
import akka.stream.stage._

/**
 * INTERNAL API
 */
private[remote] class UdpTransport(driver: UdpDriver) {

  def forRemoteAddress(address: InetSocketAddress): Flow[Frame, Frame, NotUsed] = {
    Flow.fromGraph(new UdpTransportFlow(driver, address))
  }

}

/**
 * INTERNAL API
 */
private[remote] class UdpTransportFlow(val driver: UdpDriver, val remoteAddress: InetSocketAddress)
  extends GraphStage[FlowShape[Frame, Frame]] {

  val in: Inlet[Frame] = Inlet[Frame]("UdpTransport.write")
  val out: Outlet[Frame] = Outlet[Frame]("UdpTransport.read")
  override val shape: FlowShape[Frame, Frame] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {

    private var registration: UdpDriver.Registration = _

    override def preStart(): Unit = {
      driver.command(UdpDriver.Register(
        getAsyncCallback(onRegistered),
        getAsyncCallback(_ ⇒ onWakeUp()),
        remoteAddress))
    }

    override def postStop(): Unit = {
      if (registration ne null) registration.deregister()
    }

    private def onRegistered(registration: Registration): Unit = {
      this.registration = registration
      pull(in)
      onWakeUp()
    }

    private def onWakeUp(): Unit = {
      if (UdpDriver.Debug) println("woken up")
      if (isAvailable(out)) onPull()
    }

    override def onPush(): Unit = {
      // Might drop, but higher level flow control should prevent this
      registration.sendQueue.offer(grab(in))
      pull(in)
    }

    override def onUpstreamFinish(): Unit = ()

    override def onDownstreamFinish(): Unit = ()

    override def onPull(): Unit = {
      if (registration ne null) {
        val frame = registration.rcvQueue.poll()
        if (frame ne null) push(out, frame)
        else registration.readWeakupNeeded.set(true)
      }
    }

    setHandlers(in, out, this)
  }

}