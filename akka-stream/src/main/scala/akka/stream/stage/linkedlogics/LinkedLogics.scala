/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic

import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic }
import akka.stream.stage.linkedlogic.impl.SlotsCollector.LogicInterface
import akka.stream.stage.linkedlogic.impl.TimerSlotsCollector.TimerLogicInterface
import akka.stream.stage.linkedlogic.impl.{ TimerLogicsCollector, _ }
import akka.stream.stage.linkedlogic.logics.{ InputLogic, OnceScheduledTimerLogic, PeriodicallyTimerLogic, TimerLogic }
import akka.stream.{ Inlet, Outlet, Shape }

import scala.concurrent.duration.FiniteDuration

/**
 * Represents processing logics with defined links between inlets and outlets.
 * We must to define the logic for all inlets in the shape before LinkedLogics started.
 */
final class LinkedLogics(shape: Shape)(implicit system: ActorSystem) extends GraphStageLogic(shape) { self ⇒
  private val log = Logging(system, getClass)

  private val slots = new SlotsCollector(shape, new LogicInterface {
    override def cancel[T](in: Inlet[T]): Unit = self.cancel(in)
    override def setHandler(in: Inlet[_], handler: InHandler): Unit = self.setHandler(in, handler)
    override def setHandler(out: Outlet[_], handler: OutHandler): Unit = self.setHandler(out, handler)
    override def isAvailable[T](in: Inlet[T]): Boolean = self.isAvailable(in)
    override def isAvailable[T](out: Outlet[T]): Boolean = self.isAvailable(out)
    override def hasBeenPulled[T](in: Inlet[T]): Boolean = self.hasBeenPulled(in)
    override def push[T](out: Outlet[T], elem: T): Unit = self.push(out, elem)
    override def tryPull[T](in: Inlet[T]): Unit = self.tryPull(in)
    override def completeStage(): Unit = self.completeStage()
    override def grab[T](in: Inlet[T]): T = self.grab(in)
  })

  private val logics = new InputLogicsCollector()

  override def preStart(): Unit = {
    logics.start(slots)
    super.preStart()
  }

  /**
   * Adds logic of processing elements from the inlet.
   */
  def add[In](logic: InputLogic[In]): InputLogic[In] = {
    logics.addInputLogic(logic)
    logic
  }
}

/**
 * Represents processing logics with predefined links between inlets, timers and outlets.
 */
final class TimerLinkedLogics(shape: Shape)(implicit system: ActorSystem) extends TimerGraphStageLogic(shape) { self ⇒
  private val log = Logging(system, getClass)

  private val slots = new TimerSlotsCollector(shape, new TimerLogicInterface {
    override def cancel[T](in: Inlet[T]): Unit = self.cancel(in)
    override def setHandler(in: Inlet[_], handler: InHandler): Unit = self.setHandler(in, handler)
    override def setHandler(out: Outlet[_], handler: OutHandler): Unit = self.setHandler(out, handler)
    override def isAvailable[T](in: Inlet[T]): Boolean = self.isAvailable(in)
    override def isAvailable[T](out: Outlet[T]): Boolean = self.isAvailable(out)
    override def hasBeenPulled[T](in: Inlet[T]): Boolean = self.hasBeenPulled(in)
    override def push[T](out: Outlet[T], elem: T): Unit = self.push(out, elem)
    override def tryPull[T](in: Inlet[T]): Unit = self.pull(in)
    override def completeStage(): Unit = self.completeStage()
    override def grab[T](in: Inlet[T]): T = self.grab(in)
    override def scheduleOnce(timerKey: Any, delay: FiniteDuration): Unit = self.scheduleOnce(timerKey, delay)
    override def schedulePeriodically(timerKey: Any, interval: FiniteDuration): Unit = self.schedulePeriodically(timerKey, interval)
    override def schedulePeriodicallyWithInitialDelay(timerKey: Any, initialDelay: FiniteDuration, interval: FiniteDuration): Unit =
      self.schedulePeriodicallyWithInitialDelay(timerKey, initialDelay, interval)
    override def cancelTimer(timerKey: Any): Unit = self.cancelTimer(timerKey)
  })

  private val logics = new TimerLogicsCollector()

  override def preStart(): Unit = {
    logics.start(slots)
    super.preStart()
  }

  /**
   * Adds logic of processing elements from inlet.
   */
  def add[In](logic: InputLogic[In]): InputLogic[In] = {
    logics.addInputLogic(logic)
    if (logics.isStarted()) {
      logic.start(slots)
    }
    logic
  }

  /**
   * Adds logic of processing elements from the timer.
   */
  def add[Logic <: TimerLogic](logic: Logic): Logic = {
    logics.addTimerLogic(logic)
    if (logics.isStarted()) {
      logic.start(slots)
    }
    logic
  }

  protected final override def onTimer(timerKey: Any): Unit = {
    slots.onTimer(timerKey)
  }
}
