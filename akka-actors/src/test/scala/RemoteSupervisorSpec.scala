/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import akka.serialization.BinaryString
import nio.{RemoteClient, RemoteServer}
import config.ScalaConfig._

//import com.jteigen.scalatest.JUnit4Runner
import org.junit.runner.RunWith
import org.scalatest.Suite

object Log {
  var messageLog: String = ""
  var oneWayLog: String = ""  
}
/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
//@RunWith(classOf[JUnit4Runner])
class RemoteSupervisorSpec extends junit.framework.TestCase with Suite  {

  akka.Config.config
  new Thread(new Runnable() {
     def run = {
       RemoteServer.start
     }
  }).start
  Thread.sleep(1000)

  var pingpong1: RemotePingPong1Actor = _
  var pingpong2: RemotePingPong2Actor = _
  var pingpong3: RemotePingPong3Actor = _

  def testStartServer = {
    Log.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor

    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
  }

  def testKillSingleActorOneForOne = {
    Log.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong1 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIE") {
      Log.messageLog
    }
  }

  def testCallKillCallSingleActorOneForOne = {
    Log.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      Log.messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong1 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("pingDIE") {
      Log.messageLog
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingDIEping") {
      Log.messageLog
    }
  }

  def testKillSingleActorAllForOne = {
    Log.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong1 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIE") {
      Log.messageLog
    }
  }

  def testCallKillCallSingleActorAllForOne = {
    Log.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      Log.messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong1 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("pingDIE") {
      Log.messageLog
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingDIEping") {
      Log.messageLog
    }
  }

  def testKillMultipleActorsOneForOne = {
    Log.messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong3 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIE") {
      Log.messageLog
    }
  }

  def tesCallKillCallMultipleActorsOneForOne = {
    Log.messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      Log.messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong2 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("pingpingpingDIE") {
      Log.messageLog
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEpingpingping") {
      Log.messageLog
    }
  }

  def testKillMultipleActorsAllForOne = {
    Log.messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong2 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIEDIEDIE") {
      Log.messageLog
    }
  }

  def tesCallKillCallMultipleActorsAllForOne = {
    Log.messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      Log.messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong2 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIE") {
      Log.messageLog
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIEpingpingping") {
      Log.messageLog
    }
  }

  /*
  def testOneWayKillSingleActorOneForOne = {
    Logg.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    pingpong1 ! BinaryString("Die")
    Thread.sleep(500)
    expect("DIE") {
      Logg.messageLog
    }
  }

  def testOneWayCallKillCallSingleActorOneForOne = {
    Logg.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    pingpong1 ! OneWay
    Thread.sleep(500)
    expect("oneway") {
      Logg.oneWayLog
    }
    pingpong1 ! BinaryString("Die")
    Thread.sleep(500)
    expect("DIE") {
      Logg.messageLog
    }
    pingpong1 ! OneWay
    Thread.sleep(500)
    expect("onewayoneway") {
      Logg.oneWayLog
    }
  }
*/
  
  /*
  def testOneWayKillSingleActorAllForOne = {
    Logg.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong1 ! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIE") {
      Logg.messageLog
    }
  }

  def testOneWayCallKillCallSingleActorAllForOne = {
    Logg.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      Logg.messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong1 ! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("pingDIE") {
      Logg.messageLog
    }
    expect("pong") {
      (pingpong1 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingDIEping") {
      Logg.messageLog
    }
  }

  def testOneWayKillMultipleActorsOneForOne = {
    Logg.messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong3 ! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIE") {
      Logg.messageLog
    }
  }

  def tesOneWayCallKillCallMultipleActorsOneForOne = {
    Logg.messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      Logg.messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong2 ! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("pingpingpingDIE") {
      Logg.messageLog
    }
    expect("pong") {
      (pingpong1 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEpingpingping") {
      Logg.messageLog
    }
  }

  def testOneWayKillMultipleActorsAllForOne = {
    Logg.messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    intercept(classOf[RuntimeException]) {
      pingpong2 ! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIEDIEDIE") {
      Logg.messageLog
    }
  }

  def tesOneWayCallKillCallMultipleActorsAllForOne = {
    Logg.messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup ! StartSupervisor
    Thread.sleep(500)
    expect("pong") {
      pingpong1 ! BinaryString("Ping")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingping") {
      Logg.messageLog
    }
    intercept(classOf[RuntimeException]) {
      pingpong2 ! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIE") {
      Logg.messageLog
    }
    expect("pong") {
      (pingpong1 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong2 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pong") {
      (pingpong3 ! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("pingpingpingDIEDIEDIEpingpingping") {
      Logg.messageLog
    }
  }
   */

  /*
   def testNestedSupervisorsTerminateFirstLevelActorAllForOne = {
    Logg.messageLog = ""
     val sup = getNestedSupervisorsAllForOneConf
     sup ! StartSupervisor
     intercept(classOf[RuntimeException]) {
       pingpong1 !! BinaryString("Die")
     }
     Thread.sleep(500)
     expect("DIEDIEDIE") {
       Logg.messageLog
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

    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

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

}

@serializable class RemotePingPong1Actor extends Actor {
  override def receive: PartialFunction[Any, Unit] = {
    case BinaryString("Ping") =>
      Log.messageLog += "ping"
      reply("pong")

    case OneWay =>
      Log.oneWayLog += "oneway"

    case BinaryString("Die") =>
      throw new RuntimeException("DIE")
  }
  override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
    Log.messageLog += reason.asInstanceOf[Exception].getMessage
  }
}

@serializable class RemotePingPong2Actor extends Actor {
  override def receive: PartialFunction[Any, Unit] = {
    case BinaryString("Ping") =>
      Log.messageLog += "ping"
      reply("pong")
    case BinaryString("Die") =>
      throw new RuntimeException("DIE")
  }
  override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
    Log.messageLog += reason.asInstanceOf[Exception].getMessage
  }
}

@serializable class RemotePingPong3Actor extends Actor {
  override def receive: PartialFunction[Any, Unit] = {
    case BinaryString("Ping") =>
      Log.messageLog += "ping"
      reply("pong")
    case BinaryString("Die") =>
      throw new RuntimeException("DIE")
  }

  override protected def postRestart(reason: AnyRef, config: Option[AnyRef]) {
    Log.messageLog += reason.asInstanceOf[Exception].getMessage
  }
}
