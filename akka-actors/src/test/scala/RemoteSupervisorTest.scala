/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.serialization.BinaryString
import se.scalablesolutions.akka.nio.{RemoteClient, RemoteServer}
import se.scalablesolutions.akka.config.ScalaConfig._

import org.scalatest.junit.JUnitSuite
import org.junit.Test

object Log {
  var messageLog: String = ""
  var oneWayLog: String = ""
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteSupervisorTest extends JUnitSuite {
  import Actor.Sender.Self

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

  @Test def shouldStartServer = {
    Log.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start

    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
  }

  @Test def shouldKillSingleActorOneForOne = {
    Log.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong1 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIE") {
      Log.messageLog
    }
  }

  @Test def shouldCallKillCallSingleActorOneForOne = {
    Log.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      Log.messageLog
    }
    intercept[RuntimeException] {
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

  @Test def shouldKillSingleActorAllForOne = {
    Log.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
      pingpong1 !! BinaryString("Die")
    }
    Thread.sleep(500)
    expect("DIE") {
      Log.messageLog
    }
  }

  @Test def shouldCallKillCallSingleActorAllForOne = {
    Log.messageLog = ""
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    Thread.sleep(500)
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
    Thread.sleep(500)
    expect("ping") {
      Log.messageLog
    }
    intercept[RuntimeException] {
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

  @Test def shouldKillMultipleActorsOneForOne = {
    Log.messageLog = ""
    val sup = getMultipleActorsOneForOneConf
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
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
    sup.start
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
    intercept[RuntimeException] {
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

  @Test def shouldKillMultipleActorsAllForOne = {
    Log.messageLog = ""
    val sup = getMultipleActorsAllForOneConf
    sup.start
    Thread.sleep(500)
    intercept[RuntimeException] {
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
    sup.start
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
    intercept[RuntimeException] {
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
  @Test def shouldOneWayKillSingleActorOneForOne = {
    Logg.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    Thread.sleep(500)
    pingpong1 ! BinaryString("Die")
    Thread.sleep(500)
    expect("DIE") {
      Logg.messageLog
    }
  }

  @Test def shouldOneWayCallKillCallSingleActorOneForOne = {
    Logg.messageLog = ""
    val sup = getSingleActorOneForOneSupervisor
    sup.start
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
 @Test def shouldOneWayKillSingleActorAllForOne = {
   Logg.messageLog = ""
   val sup = getSingleActorAllForOneSupervisor
   sup.start
   Thread.sleep(500)
   intercept[RuntimeException] {
     pingpong1 ! BinaryString("Die")
   }
   Thread.sleep(500)
   expect("DIE") {
     Logg.messageLog
   }
 }

 @Test def shouldOneWayCallKillCallSingleActorAllForOne = {
   Logg.messageLog = ""
   val sup = getSingleActorAllForOneSupervisor
   sup.start
   Thread.sleep(500)
   expect("pong") {
     (pingpong1 ! BinaryString("Ping")).getOrElse("nil")
   }
   Thread.sleep(500)
   expect("ping") {
     Logg.messageLog
   }
   intercept[RuntimeException] {
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

 @Test def shouldOneWayKillMultipleActorsOneForOne = {
   Logg.messageLog = ""
   val sup = getMultipleActorsOneForOneConf
   sup.start
   Thread.sleep(500)
   intercept[RuntimeException] {
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
   sup.start
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
   intercept[RuntimeException] {
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

 @Test def shouldOneWayKillMultipleActorsAllForOne = {
   Logg.messageLog = ""
   val sup = getMultipleActorsAllForOneConf
   sup.start
   Thread.sleep(500)
   intercept[RuntimeException] {
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
   sup.start
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
   intercept[RuntimeException] {
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
   @Test def shouldNestedSupervisorsTerminateFirstLevelActorAllForOne = {
    Logg.messageLog = ""
     val sup = getNestedSupervisorsAllForOneConf
     sup.start
     intercept[RuntimeException] {
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

    val factory = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(AllForOne, 3, 100),
        Supervise(
          pingpong1,
          LifeCycle(Permanent))
            :: Nil))

    factory.newInstance
  }

  def getSingleActorOneForOneSupervisor: Supervisor = {
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

    val factory = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100),
        Supervise(
          pingpong1,
          LifeCycle(Permanent))
            :: Nil))
    factory.newInstance
  }

  def getMultipleActorsAllForOneConf: Supervisor = {
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

    val factory = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(AllForOne, 3, 100),
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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

    val factory = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100),
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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)

    val factory = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(AllForOne, 3, 100),
        Supervise(
          pingpong1,
          LifeCycle(Permanent))
            ::
            SupervisorConfig(
              RestartStrategy(AllForOne, 3, 100),
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
}

@serializable class RemotePingPong1Actor extends Actor {
  def receive = {
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
  def receive = {
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
  def receive = {
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
