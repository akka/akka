/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.dispatch.Dispatchers
import se.scalablesolutions.akka.{OneWay, Die, Ping}

import org.scalatest.junit.JUnitSuite
import org.junit.Test

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorTest extends JUnitSuite {
  import Actor.Sender.Self

  var messageLog: String = ""
  var oneWayLog: String = ""
  
  var pingpong1: PingPong1Actor = _
  var pingpong2: PingPong2Actor = _
  var pingpong3: PingPong3Actor = _

  @Test def shouldStartServer = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start

    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
  }

  @Test def shouldKillSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong1 !! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  @Test def shouldCallKillCallSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      messageLog
    }
    intercept[RuntimeException] {
      pingpong1 !! Die
    }
    Thread.sleep(500)
    expect("pingDIE") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingDIEping") {
      messageLog
    }
  }

  @Test def shouldKillSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong1 !! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  @Test def shouldCallKillCallSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      messageLog
    }
    intercept[RuntimeException] {
      pingpong1 !! Die
    }
    Thread.sleep(500)
    expect("pingDIE") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingDIEping") {
      messageLog
    }
  }

  @Test def shouldKillMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong3 !! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  def tesCallKillCallMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      messageLog
    }
    intercept[RuntimeException] {
      pingpong2 !! Die
    }
    Thread.sleep(500)
    expect("pingpingpingDIE") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEpingpingping") {
      messageLog
    }
  }

  @Test def shouldKillMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong2 !! Die
    }
    Thread.sleep(500)
    expect("DIEDIEDIE") {
      messageLog
    }
  }

  def tesCallKillCallMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      messageLog
    }
    intercept[RuntimeException] {
      pingpong2 !! Die
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIE") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIEpingpingping") {
      messageLog
    }
  }

  @Test def shouldOneWayKillSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    Thread.sleep(500)
    pingpong1 ! Die
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  @Test def shouldOneWayCallKillCallSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    Thread.sleep(500)
    pingpong1 ! OneWay
    Thread.sleep(500)
    expect("oneway") {
      oneWayLog
    }
    pingpong1 ! Die
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
    pingpong1 ! OneWay
    Thread.sleep(500)
    expect("onewayoneway") {
      oneWayLog
    }
  }

  /*
  @Test def shouldOneWayKillSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong1 ! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  @Test def shouldOneWayCallKillCallSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      messageLog
    }
    intercept[RuntimeException] {
      pingpong1 ! Die
    }
    Thread.sleep(500)
    expect("pingDIE") {
      messageLog
    }
    expect("pong") {
      (pingpong1 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingDIEping") {
      messageLog
    }
  }

  @Test def shouldOneWayKillMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong3 ! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  def tesOneWayCallKillCallMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      messageLog
    }
    intercept[RuntimeException] {
      pingpong2 ! Die
    }
    Thread.sleep(500)
    expect("pingpingpingDIE") {
      messageLog
    }
    expect("pong") {
      (pingpong1 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEpingpingping") {
      messageLog
    }
  }

  @Test def shouldOneWayKillMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong2 ! Die
    }
    Thread.sleep(500)
    expect("DIEDIEDIE") {
      messageLog
    }
  }

  def tesOneWayCallKillCallMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup.start
    Thread.sleep(500)
    expect("pong") {
      pingpong1 ! Ping
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      messageLog
    }
    intercept[RuntimeException] {
      pingpong2 ! Die
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIE") {
      messageLog
    }
    expect("pong") {
      (pingpong1 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIEpingpingping") {
      messageLog
    }
  }
   */

  /*
   @Test def shouldNestedSupervisorsTerminateFirstLevelActorAllForOne = {
    messageLog = ""
     val sup = getNestedSupervisorsAllForOneConf
     sup.start
     intercept[RuntimeException] {
       pingpong1 !! Die
     }
     Thread.sleep(500)
     expect("DIEDIEDIE") {
       messageLog
     }
   }
*/
  
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
        messageLog += "ping"
        reply("pong")

      case OneWay =>
        oneWayLog += "oneway"
      
      case Die =>
        throw new RuntimeException("DIE")
    }
    override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
      messageLog += reason.asInstanceOf[Exception].getMessage      
    }
  }

  class PingPong2Actor extends Actor {
    def receive = {
      case Ping =>
        messageLog += "ping"
        reply("pong")
      case Die =>
        throw new RuntimeException("DIE")
    }
    override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
      messageLog += reason.asInstanceOf[Exception].getMessage
    }
  }

  class PingPong3Actor extends Actor {
    def receive = {
      case Ping =>
        messageLog += "ping"
        reply("pong")
      case Die =>
        throw new RuntimeException("DIE")
    }

    override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
      messageLog += reason.asInstanceOf[Exception].getMessage
    }
  }
}
