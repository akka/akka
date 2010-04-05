/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import _root_.java.util.concurrent.{TimeUnit, BlockingQueue, LinkedBlockingQueue}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.dispatch.Dispatchers
import se.scalablesolutions.akka.{OneWay, Die, Ping}

import org.scalatest.junit.JUnitSuite
import org.junit.Test

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorSpec extends JUnitSuite {

  var messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  var oneWayLog: BlockingQueue[String] = new LinkedBlockingQueue[String]

  var pingpong1: PingPong1Actor = _
  var pingpong2: PingPong2Actor = _
  var pingpong3: PingPong3Actor = _

  @Test def shouldStartServer = {
    messageLog.clear
    val sup = getSingleActorAllForOneSupervisor
    sup.start

    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
  }

  @Test def shouldKillSingleActorOneForOne = {
    messageLog.clear
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    intercept[RuntimeException] {
      pingpong1 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorOneForOne = {
    messageLog.clear
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillSingleActorAllForOne = {
    messageLog.clear
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    intercept[RuntimeException] {
      pingpong1 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorAllForOne = {
    messageLog.clear
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsOneForOne = {
    messageLog.clear
    val sup = getMultipleActorsOneForOneConf
    sup.start
    intercept[RuntimeException] {
      pingpong3 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  def tesCallKillCallMultipleActorsOneForOne = {
    messageLog.clear
    val sup = getMultipleActorsOneForOneConf
    sup.start
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong2 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsAllForOne = {
    messageLog.clear
    val sup = getMultipleActorsAllForOneConf
    sup.start
    intercept[RuntimeException] {
      pingpong2 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  def tesCallKillCallMultipleActorsAllForOne = {
    messageLog.clear
    val sup = getMultipleActorsAllForOneConf
    sup.start
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong2 !! Die
    }

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldOneWayKillSingleActorOneForOne = {
    messageLog.clear
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    pingpong1 ! Die

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldOneWayCallKillCallSingleActorOneForOne = {
    messageLog.clear
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    pingpong1 ! OneWay

    expect("oneway") {
      oneWayLog.poll(1, TimeUnit.SECONDS)
    }
    pingpong1 ! Die

    expect("DIE") {
      messageLog.poll(1, TimeUnit.SECONDS)
    }
    pingpong1 ! OneWay

    expect("oneway") {
      oneWayLog.poll(1, TimeUnit.SECONDS)
    }
  }

  // =============================================
  // Creat some supervisors with different configurations

  def getSingleActorAllForOneSupervisor: Supervisor = {
    pingpong1 = new PingPong1Actor
    
    val factory = SupervisorFactory(
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100, List(classOf[Exception])),
          Supervise(
            pingpong1,
            LifeCycle(Permanent))
          :: Nil))
    factory.newInstance
  }

  def getSingleActorOneForOneSupervisor: Supervisor = {
    pingpong1 = new PingPong1Actor

    val factory = SupervisorFactory(
        SupervisorConfig(
          RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
          Supervise(
            pingpong1,
            LifeCycle(Permanent))
          :: Nil))
    factory.newInstance
  }

  def getMultipleActorsAllForOneConf: Supervisor = {
    pingpong1 = new PingPong1Actor
    pingpong2 = new PingPong2Actor
    pingpong3 = new PingPong3Actor

    val factory = SupervisorFactory(
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100, List(classOf[Exception])),
          Supervise(
            pingpong1,
            LifeCycle(Permanent))
          ::
          Supervise(
            pingpong2,
            LifeCycle(Permanent))
          ::
          Supervise(
            pingpong3,
            LifeCycle(Permanent))
          :: Nil))
    factory.newInstance
  }

  def getMultipleActorsOneForOneConf: Supervisor = {
    pingpong1 = new PingPong1Actor
    pingpong2 = new PingPong2Actor
    pingpong3 = new PingPong3Actor

    val factory = SupervisorFactory(
        SupervisorConfig(
          RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
          Supervise(
            pingpong1,
            LifeCycle(Permanent))
          ::
          Supervise(
            pingpong2,
            LifeCycle(Permanent))
          ::
          Supervise(
            pingpong3,
            LifeCycle(Permanent))
          :: Nil))
    factory.newInstance
  }

  def getNestedSupervisorsAllForOneConf: Supervisor = {
    pingpong1 = new PingPong1Actor
    pingpong2 = new PingPong2Actor
    pingpong3 = new PingPong3Actor

    val factory = SupervisorFactory(
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100, List(classOf[Exception])),
          Supervise(
            pingpong1,
            LifeCycle(Permanent))
          ::
          SupervisorConfig(
            RestartStrategy(AllForOne, 3, 100, List(classOf[Exception])),
            Supervise(
              pingpong2,
              LifeCycle(Permanent))
            ::
            Supervise(
              pingpong3,
              LifeCycle(Permanent))
            :: Nil)
          :: Nil))
     factory.newInstance
   }

  class PingPong1Actor extends Actor {
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")

      case OneWay =>
        oneWayLog.put("oneway")
      
      case Die =>
        throw new RuntimeException("DIE")
    }
    override protected def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }

  class PingPong2Actor extends Actor {
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")
      case Die =>
        throw new RuntimeException("DIE")
    }
    override protected def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }

  class PingPong3Actor extends Actor {
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")
      case Die =>
        throw new RuntimeException("DIE")
    }

    override protected def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }
}
