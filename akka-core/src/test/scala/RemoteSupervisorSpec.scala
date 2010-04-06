/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import _root_.java.util.concurrent.{LinkedBlockingQueue, TimeUnit, BlockingQueue}
import se.scalablesolutions.akka.serialization.BinaryString
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.remote.{RemoteNode, RemoteServer}
import se.scalablesolutions.akka.OneWay
import se.scalablesolutions.akka.dispatch.Dispatchers

import org.scalatest.junit.JUnitSuite
import org.junit.Test

object Log {
  var messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  var oneWayLog: String = ""
}

@serializable class RemotePingPong1Actor extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)
  def receive = {
    case BinaryString("Ping") =>
      Log.messageLog.put("ping")
      reply("pong")

    case OneWay =>
      Log.oneWayLog += "oneway"

    case BinaryString("Die") =>
      throw new RuntimeException("DIE")
  }

  override protected def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

@serializable class RemotePingPong2Actor extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)
  def receive = {
    case BinaryString("Ping") =>
      Log.messageLog.put("ping")
      reply("pong")
    case BinaryString("Die") =>
      throw new RuntimeException("DIE")
  }

  override protected def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

@serializable class RemotePingPong3Actor extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)
  def receive = {
    case BinaryString("Ping") =>
      Log.messageLog.put("ping")
      reply("pong")
    case BinaryString("Die") =>
      throw new RuntimeException("DIE")
  }

  override protected def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteSupervisorSpec extends JUnitSuite {

  se.scalablesolutions.akka.config.Config.config

  new Thread(new Runnable() {
    def run = {
      RemoteNode.start(RemoteServer.HOSTNAME, 9988)
    }
  }).start
  Thread.sleep(1000)

  var pingpong1: RemotePingPong1Actor = _
  var pingpong2: RemotePingPong2Actor = _
  var pingpong3: RemotePingPong3Actor = _

  @Test def shouldStartServer = {
    Log.messageLog.clear
    val sup = getSingleActorAllForOneSupervisor
    sup.start

    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
  }

  @Test def shouldKillSingleActorOneForOne = {
    Log.messageLog.clear
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    intercept[RuntimeException] {
      pingpong1 !! BinaryString("Die")
    }
    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorOneForOne = {
    Log.messageLog.clear
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! BinaryString("Die")
    }

    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillSingleActorAllForOne = {
    Log.messageLog.clear
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    intercept[RuntimeException] {
      pingpong1 !! BinaryString("Die")
    }

    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorAllForOne = {
    Log.messageLog.clear
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! BinaryString("Die")
    }

    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsOneForOne = {
    Log.messageLog.clear
    val sup = getMultipleActorsOneForOneConf
    sup.start
    intercept[RuntimeException] {
      pingpong3 !! BinaryString("Die")
    }

    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  def tesCallKillCallMultipleActorsOneForOne = {
    Log.messageLog.clear
    val sup = getMultipleActorsOneForOneConf
    sup.start
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong2 !! BinaryString("Die")
    }

    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsAllForOne = {
    Log.messageLog.clear
    val sup = getMultipleActorsAllForOneConf
    sup.start
    intercept[RuntimeException] {
      pingpong2 !! BinaryString("Die")
    }

    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
  }

  def tesCallKillCallMultipleActorsAllForOne = {
    Log.messageLog.clear
    val sup = getMultipleActorsAllForOneConf
    sup.start
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong2 !! BinaryString("Die")
    }

    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("DIE") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! BinaryString("Ping")).getOrElse("nil")
    }

    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
    }
    expect("ping") {
      Log.messageLog.poll(1, TimeUnit.SECONDS)
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

    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, 9988)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, 9988)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, 9988)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, 9988)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, 9988)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, 9988)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, 9988)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, 9988)

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
    pingpong1 = new RemotePingPong1Actor
    pingpong1.makeRemote(RemoteServer.HOSTNAME, 9988)
    pingpong2 = new RemotePingPong2Actor
    pingpong2.makeRemote(RemoteServer.HOSTNAME, 9988)
    pingpong3 = new RemotePingPong3Actor
    pingpong3.makeRemote(RemoteServer.HOSTNAME, 9988)

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
}
