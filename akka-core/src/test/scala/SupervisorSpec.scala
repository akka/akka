/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.dispatch.Dispatchers
import se.scalablesolutions.akka.{OneWay, Die, Ping}
import Actor._

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit, BlockingQueue, LinkedBlockingQueue}

object SupervisorSpec {
  var messageLog = new LinkedBlockingQueue[String]
  var oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }
  
  class PingPong1Actor extends Actor {
    import self._
    dispatcher = Dispatchers.newThreadBasedDispatcher(self)
    timeout = 1000
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")

      case OneWay =>
        oneWayLog.put("oneway")

      case Die =>
        throw new RuntimeException("DIE")
    }
    override def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }

  class PingPong2Actor extends Actor {
    import self._
    timeout = 1000
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")
      case Die =>
        throw new RuntimeException("DIE")
    }
    override def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }

  class PingPong3Actor extends Actor {
    import self._
    timeout = 1000
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")
      case Die =>
        throw new RuntimeException("DIE")
    }

    override def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorSpec extends JUnitSuite {
  import SupervisorSpec._
  
  var pingpong1: ActorRef = _
  var pingpong2: ActorRef = _
  var pingpong3: ActorRef = _

  @Test def shouldStartServer = {
    clearMessageLogs
    val sup = getSingleActorAllForOneSupervisor
    sup.start

    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }
  }

  @Test def shouldStartServerForNestedSupervisorHierarchy = {
    clearMessageLogs
    val sup = getNestedSupervisorsAllForOneConf
    sup.start

    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }
  }

  @Test def shouldKillSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillSingleActorAllForOne = {
    clearMessageLogs
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorAllForOne = {
    clearMessageLogs
    val sup = getSingleActorAllForOneSupervisor
    sup.start
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsOneForOne1 = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf
    sup.start
    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsOneForOne2 = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf
    sup.start
    intercept[RuntimeException] {
      pingpong3 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillCallMultipleActorsOneForOne = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf
    sup.start
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong2 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsAllForOne = {
    clearMessageLogs
    val sup = getMultipleActorsAllForOneConf
    sup.start
    intercept[RuntimeException] {
      pingpong2 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallMultipleActorsAllForOne = {
    clearMessageLogs
    val sup = getMultipleActorsAllForOneConf
    sup.start
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong2 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldOneWayKillSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    pingpong1 ! Die

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldOneWayCallKillCallSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor
    sup.start
    pingpong1 ! OneWay

    expect("oneway") {
      oneWayLog.poll(5, TimeUnit.SECONDS)
    }
    pingpong1 ! Die

    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    pingpong1 ! OneWay
    
    expect("oneway") {
      oneWayLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldRestartKilledActorsForNestedSupervisorHierarchy = {
    clearMessageLogs
    val sup = getNestedSupervisorsAllForOneConf
    sup.start

    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong2 !! (Die, 5000)
    }

    expect("DIE") {
      messageLog.poll(5 , TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("DIE") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  // =============================================
  // Create some supervisors with different configurations

  def getSingleActorAllForOneSupervisor: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start

    Supervisor(
      SupervisorConfig(
        RestartStrategy(AllForOne, 3, 5000, List(classOf[Exception])),
        Supervise(
          pingpong1,
          LifeCycle(Permanent))
        :: Nil))
  }

  def getSingleActorOneForOneSupervisor: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start

    Supervisor(
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 5000, List(classOf[Exception])),
        Supervise(
          pingpong1,
          LifeCycle(Permanent))
        :: Nil))
  }

  def getMultipleActorsAllForOneConf: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start
    pingpong2 = actorOf[PingPong2Actor].start
    pingpong3 = actorOf[PingPong3Actor].start

    Supervisor(
      SupervisorConfig(
        RestartStrategy(AllForOne, 3, 5000, List(classOf[Exception])),
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
  }

  def getMultipleActorsOneForOneConf: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start
    pingpong2 = actorOf[PingPong2Actor].start
    pingpong3 = actorOf[PingPong3Actor].start

    Supervisor(
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 5000, List(classOf[Exception])),
        Supervise(
          pingpong3,
          LifeCycle(Permanent))
        ::
        Supervise(
          pingpong2,
          LifeCycle(Permanent))
        ::
        Supervise(
          pingpong1,
          LifeCycle(Permanent))
        :: Nil))
  }

  def getNestedSupervisorsAllForOneConf: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start
    pingpong2 = actorOf[PingPong2Actor].start
    pingpong3 = actorOf[PingPong3Actor].start

    Supervisor(
      SupervisorConfig(
        RestartStrategy(AllForOne, 3, 5000, List(classOf[Exception])),
        Supervise(
          pingpong1,
          LifeCycle(Permanent))
        ::
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 5000, Nil),
          Supervise(
            pingpong2,
            LifeCycle(Permanent))
          ::
          Supervise(
            pingpong3,
            LifeCycle(Permanent))
          :: Nil)
      :: Nil))
   }
}
