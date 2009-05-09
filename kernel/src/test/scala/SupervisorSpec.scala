/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import se.scalablesolutions.akka.kernel.config.ScalaConfig.{SupervisorConfig, Worker, LifeCycle, RestartStrategy, OneForOne, AllForOne, Permanent}
import scala.actors._
import scala.actors.Actor._
import scala.collection.Map
import scala.collection.mutable.HashMap

import com.jteigen.scalatest.JUnit4Runner
import org.junit.runner.RunWith
import org.scalatest._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@RunWith(classOf[JUnit4Runner])
class SupervisorSpec extends Suite {

  var messageLog: String = ""
  val pingpong1 = new GenericServerContainer("pingpong1", () => new PingPong1Actor)
  val pingpong2 = new GenericServerContainer("pingpong2", () => new PingPong2Actor)
  val pingpong3 = new GenericServerContainer("pingpong3", () => new PingPong3Actor)

  pingpong1.setTimeout(100)
  pingpong2.setTimeout(100)
  pingpong3.setTimeout(100)

  def testStartServer = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! Start

    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
  }

  def testGetServer = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! Start
    val server = sup.getServerOrElse("pingpong1", throw new RuntimeException("server not found"))
    assert(server.isInstanceOf[GenericServerContainer])
    assert(server === pingpong1)
  }

  def testGetServerOrFail = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! Start
    intercept(classOf[RuntimeException]) {
      sup.getServerOrElse("wrong_name", throw new RuntimeException("server not found"))
    }
  }

  def testKillSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! Start

    intercept(classOf[RuntimeException]) {
      pingpong1 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("oneforone") {
      messageLog
    }
  }

  def testCallKillCallSingleActorOneForOne = {
    messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! Start

    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("ping") {
      messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong1 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("pingoneforone") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pingoneforoneping") {
      messageLog
    }
  }

  def testKillSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! Start
    intercept(classOf[RuntimeException]) {
      pingpong1 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("allforone") {
      messageLog
    }
  }

  def testCallKillCallSingleActorAllForOne = {
    messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! Start
    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("ping") {
      messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong1 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("pingallforone") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pingallforoneping") {
      messageLog
    }
  }

  def testKillMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! Start
    intercept(classOf[RuntimeException]) {
      pingpong3 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("oneforone") {
      messageLog
    }
  }

  def tesCallKillCallMultipleActorsOneForOne = {
    messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! Start
    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong2 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong3 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pingpingping") {
      messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong2 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("pingpingpingoneforone") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong2 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong3 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pingpingpingoneforonepingpingping") {
      messageLog
    }
  }

  def testKillMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! Start
    intercept(classOf[RuntimeException]) {
      pingpong2 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("allforoneallforoneallforone") {
      messageLog
    }
  }

  def tesCallKillCallMultipleActorsAllForOne = {
    messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! Start
    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong2 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong3 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pingpingping") {
      messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong2 !!! (Die, throw new RuntimeException("TIME OUT"))
    }
    Thread.sleep(100)
    expect("pingpingpingallforoneallforoneallforone") {
      messageLog
    }
    expect("pong") {
      (pingpong1 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong2 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pong") {
      (pingpong3 !!! Ping).getOrElse("nil")
    }
    Thread.sleep(100)
    expect("pingpingpingallforoneallforoneallforonepingpingping") {
      messageLog
    }
  }

   def testTerminateFirstLevelActorAllForOne = {
    messageLog = ""
     val sup = getNestedSupervisorsAllForOneConf
     sup ! Start
     intercept(classOf[RuntimeException]) {
       pingpong1 !!! (Die, throw new RuntimeException("TIME OUT"))
     }
     Thread.sleep(100)
     expect("allforoneallforoneallforone") {
       messageLog
     }
   }


  // =============================================
  // Creat some supervisors with different configurations

  def getSingleActorAllForOneSupervisor: Supervisor = {

    // Create an abstract SupervisorContainer that works for all implementations
    // of the different Actors (Services).
    //
    // Then create a concrete container in which we mix in support for the specific
    // implementation of the Actors we want to use.

    object factory extends TestSupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100),
          Worker(
            pingpong1,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getSingleActorOneForOneSupervisor: Supervisor = {
    object factory extends TestSupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(OneForOne, 3, 100),
          Worker(
            pingpong1,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getMultipleActorsAllForOneConf: Supervisor = {
    object factory extends TestSupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100),
          Worker(
            pingpong1,
            LifeCycle(Permanent, 100))
          ::
          Worker(
            pingpong2,
            LifeCycle(Permanent, 100))
          ::
          Worker(
            pingpong3,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getMultipleActorsOneForOneConf: Supervisor = {
    object factory extends TestSupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(OneForOne, 3, 100),
          Worker(
            pingpong1,
            LifeCycle(Permanent, 100))
          ::
          Worker(
            pingpong2,
            LifeCycle(Permanent, 100))
          ::
          Worker(
            pingpong3,
            LifeCycle(Permanent, 100))
          :: Nil)
      }
    }
    factory.newSupervisor
  }

  def getNestedSupervisorsAllForOneConf: Supervisor = {
    object factory extends TestSupervisorFactory {
      override def getSupervisorConfig: SupervisorConfig = {
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 100),
          Worker(
            pingpong1,
            LifeCycle(Permanent, 100))
          ::
          SupervisorConfig(
            RestartStrategy(AllForOne, 3, 100),
            Worker(
              pingpong2,
              LifeCycle(Permanent, 100))
            ::
            Worker(
              pingpong3,
              LifeCycle(Permanent, 100))
            :: Nil)
          :: Nil)
       }
     }
     factory.newSupervisor
   }

  class PingPong1Actor extends GenericServer {
    override def body: PartialFunction[Any, Unit] = {
      case Ping =>
        messageLog += "ping"
        reply("pong")
      case Die =>
        throw new RuntimeException("Recieved Die message")
    }
  }

  class PingPong2Actor extends GenericServer {
    override def body: PartialFunction[Any, Unit] = {
      case Ping =>
        messageLog += "ping"
        reply("pong")
      case Die =>
        throw new RuntimeException("Recieved Die message")
    }
  }

  class PingPong3Actor extends GenericServer {
    override def body: PartialFunction[Any, Unit] = {
      case Ping =>
        messageLog += "ping"
        reply("pong")
      case Die =>
        throw new RuntimeException("Recieved Die message")
    }
  }

  // =============================================

  class TestAllForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int) extends AllForOneStrategy(maxNrOfRetries, withinTimeRange) {
    override def postRestart(serverContainer: GenericServerContainer) = {
      messageLog += "allforone"
    }
  }

  class TestOneForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int) extends OneForOneStrategy(maxNrOfRetries, withinTimeRange) {
    override def postRestart(serverContainer: GenericServerContainer) = {
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
}






