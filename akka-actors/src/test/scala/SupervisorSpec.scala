/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import actor.{Supervisor, SupervisorFactory, Actor, StartSupervisor}
import config.ScalaConfig._

//import com.jteigen.scalatest.JUnit4Runner
import org.junit.runner.RunWith
import org.scalatest.Suite

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
//@RunWith(classOf[JUnit4Runner])
class SupervisorSpec extends junit.framework.TestCase with Suite {

  var messageLog: String = ""
  var oneWayLog: String = ""
  
  var pingpong1: PingPong1Actor = _
  var pingpong2: PingPong2Actor = _
  var pingpong3: PingPong3Actor = _

  def testStartServer = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor

    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
  }

  def testKillSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong1 !! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  def testCallKillCallSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      messageLog
    }
    intercept(classOf[RuntimeException]) {
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

  def testKillSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong1 !! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  def testCallKillCallSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      messageLog
    }
    intercept(classOf[RuntimeException]) {
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

  def testKillMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
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
    sup ! StartSupervisor
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
    intercept(classOf[RuntimeException]) {
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

  def testKillMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
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
    sup ! StartSupervisor
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
    intercept(classOf[RuntimeException]) {
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

  def testOneWayKillSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    pingpong1 ! Die
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  def testOneWayCallKillCallSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
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
  def testOneWayKillSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong1 ! Die
    }
    Thread.sleep(500)
    expect("DIE") {
      messageLog
    }
  }

  def testOneWayCallKillCallSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 ! Ping).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      messageLog
    }
    intercept(classOf[RuntimeException]) {
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

  def testOneWayKillMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
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
    sup ! StartSupervisor
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
    intercept(classOf[RuntimeException]) {
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

  def testOneWayKillMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
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
    sup ! StartSupervisor
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
    intercept(classOf[RuntimeException]) {
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
   def testNestedSupervisorsTerminateFirstLevelActorAllForOne = {
    messageLog = ""
     val sup = getNestedSupervisorsAllForOneConf
     sup ! StartSupervisor
     intercept(classOf[RuntimeException]) {
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

    // Create an abstract SupervisorContainer that works for all implementations
    // of the different Actors (Services).
    //
    // Then create a concrete container in which we mix in support for the specific
    // implementation of the Actors we want to use.

    pingpong1 = new PingPong1Actor
    
    object factory extends SupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100),
          Supervise(
            pingpong1,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getSingleActorOneForOneSupervisor: Supervisor = {
    pingpong1 = new PingPong1Actor

    object factory extends SupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(OneForOne, 3, 100),
          Supervise(
            pingpong1,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getMultipleActorsAllForOneConf: Supervisor = {
    pingpong1 = new PingPong1Actor
    pingpong2 = new PingPong2Actor
    pingpong3 = new PingPong3Actor

    object factory extends SupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100),
          Supervise(
            pingpong1,
            LifeCycle(Permanent, 100))
          ::
          Supervise(
            pingpong2,
            LifeCycle(Permanent, 100))
          ::
          Supervise(
            pingpong3,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getMultipleActorsOneForOneConf: Supervisor = {
    pingpong1 = new PingPong1Actor
    pingpong2 = new PingPong2Actor
    pingpong3 = new PingPong3Actor

    object factory extends SupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(OneForOne, 3, 100),
          Supervise(
            pingpong1,
            LifeCycle(Permanent, 100))
          ::
          Supervise(
            pingpong2,
            LifeCycle(Permanent, 100))
          ::
          Supervise(
            pingpong3,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getNestedSupervisorsAllForOneConf: Supervisor = {
    pingpong1 = new PingPong1Actor
    pingpong2 = new PingPong2Actor
    pingpong3 = new PingPong3Actor

    object factory extends SupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100),
          Supervise(
            pingpong1,
            LifeCycle(Permanent, 100))
          ::
          SupervisorConfig(
            RestartStrategy(AllForOne, 3, 100),
            Supervise(
              pingpong2,
              LifeCycle(Permanent, 100))
            ::
            Supervise(
              pingpong3,
              LifeCycle(Permanent, 100))
            :: Nil)
          :: Nil)
       }
     }
     factory.newSupervisor
   }

  class PingPong1Actor extends Actor {
    override def receive: PartialFunction[Any, Unit] = {
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
    override def receive: PartialFunction[Any, Unit] = {
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
    override def receive: PartialFunction[Any, Unit] = {
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

  // =============================================
/*
  class TestAllForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int) extends AllForOneStrategy(maxNrOfRetries, withinTimeRange) {
    override def postRestart(serverContainer: ActorContainer) = {
      messageLog += "allforone"
    }
  }

  class TestOneForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int) extends OneForOneStrategy(maxNrOfRetries, withinTimeRange) {
    override def postRestart(serverContainer: ActorContainer) = {
      messageLog += "oneforone"
    }
  }

  abstract class TestSupervisorFactory extends SupervisorFactory {
    override def create(strategy: RestartStrategy): Supervisor = strategy match {
      case RestartStrategy(scheme, maxNrOfRetries, timeRange) =>
        scheme match {
          case AllForOne => new Supervisor(new TestAllForOneStrategy(maxNrOfRetries, timeRange))
          case OneForOne => new Supervisor(new TestOneForOneStrategy(maxNrOfRetries, timeRange))
        }
    }
  }
  */
}
