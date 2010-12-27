/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.config.Supervision._
import akka.{OneWay, Die, Ping}
import Actor._

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent. {CountDownLatch, TimeUnit, LinkedBlockingQueue}

object SupervisorSpec {
  var messageLog = new LinkedBlockingQueue[String]
  var oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }

  class PingPong1Actor extends Actor {
    import self._
    //dispatcher = Dispatchers.newThreadBasedDispatcher(self)
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")

      case OneWay =>
        oneWayLog.put("oneway")

      case Die =>
        println("******************** GOT DIE 1")
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
    override def postRestart(reason: Throwable) {
      println("******************** restart 1")
      messageLog.put(reason.getMessage)
    }
  }

  class PingPong2Actor extends Actor {
    import self._
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")
      case Die =>
        println("******************** GOT DIE 2")
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
    override def postRestart(reason: Throwable) {
      println("******************** restart 2")
      messageLog.put(reason.getMessage)
    }
  }

  class PingPong3Actor extends Actor {
    import self._
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")
      case Die =>
        println("******************** GOT DIE 3")
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }

    override def postRestart(reason: Throwable) {
      println("******************** restart 3")
      messageLog.put(reason.getMessage)
    }
  }

  class TemporaryActor extends Actor {
    import self._
    lifeCycle = Temporary
    def receive = {
      case Ping =>
        messageLog.put("ping")
        reply("pong")
      case Die =>
        println("******************** GOT DIE 3")
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }

    override def postRestart(reason: Throwable) {
      println("******************** restart temporary")
      messageLog.put(reason.getMessage)
    }
  }

  class Master extends Actor {
    self.faultHandler = OneForOneStrategy(List(classOf[Exception]), 5, 1000)
    val temp = self.spawnLink[TemporaryActor]
    override def receive = {
      case Die => temp !! (Die, 5000)
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
  var temporaryActor: ActorRef = _

/*
  @Test def shouldStartServer = {
    clearMessageLogs
    val sup = getSingleActorAllForOneSupervisor
    sup.start

    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }
  }
*/
  @Test def shoulNotRestartProgrammaticallyLinkedTemporaryActor = {
    clearMessageLogs
    val master = actorOf[Master].start

    intercept[RuntimeException] {
      master !! (Die, 5000)
    }

    Thread.sleep(1000)
    assert(messageLog.size === 0)
  }

  @Test def shoulNotRestartTemporaryActor = {
    clearMessageLogs
    val sup = getTemporaryActorAllForOneSupervisor

    intercept[RuntimeException] {
      temporaryActor !! (Die, 5000)
    }

    Thread.sleep(1000)
    assert(messageLog.size === 0)
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

    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor

    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
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

    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorAllForOne = {
    clearMessageLogs
    val sup = getSingleActorAllForOneSupervisor

    expect("pong") {
      (pingpong1 !! (Ping, 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
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

    intercept[RuntimeException] {
      pingpong1 !! (Die, 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsOneForOne2 = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf

    intercept[RuntimeException] {
      pingpong3 !! (Die, 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillCallMultipleActorsOneForOne = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf

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

    expect("Expected exception; to test fault-tolerance") {
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

    intercept[RuntimeException] {
      pingpong2 !! (Die, 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallMultipleActorsAllForOne = {
    clearMessageLogs
    val sup = getMultipleActorsAllForOneConf

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

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("Expected exception; to test fault-tolerance") {
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

    pingpong1 ! Die

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldOneWayCallKillCallSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor

    pingpong1 ! OneWay

    expect("oneway") {
      oneWayLog.poll(5, TimeUnit.SECONDS)
    }
    pingpong1 ! Die

    expect("Expected exception; to test fault-tolerance") {
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

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5 , TimeUnit.SECONDS)
    }
    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("Expected exception; to test fault-tolerance") {
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

  @Test def shouldAttemptRestartWhenExceptionDuringRestart {
    val inits = new AtomicInteger(0)
    val dyingActor = actorOf(new Actor {
      self.lifeCycle = Permanent
      log.slf4j.debug("Creating dying actor, attempt: " + inits.incrementAndGet)

      if (!(inits.get % 2 != 0))
        throw new IllegalStateException("Don't wanna!")


      def receive = {
        case Ping => self.reply_?("pong")
        case Die => throw new Exception("expected")
      }
    })

    val supervisor =
      Supervisor(
        SupervisorConfig(
          OneForOneStrategy(classOf[Exception] :: Nil,3,10000),
          Supervise(dyingActor,Permanent) :: Nil))

    intercept[Exception] {
      dyingActor !! (Die, 5000)
    }

    expect("pong") {
      (dyingActor !! (Ping, 5000)).getOrElse("nil")
    }

    expect(3) { inits.get }
    supervisor.shutdown
  }

  // =============================================
  // Create some supervisors with different configurations

  def getTemporaryActorAllForOneSupervisor: Supervisor = {
    temporaryActor = actorOf[TemporaryActor].start

    Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 5000),
        Supervise(
          temporaryActor,
          Temporary)
        :: Nil))
  }

  def getSingleActorAllForOneSupervisor: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start

    Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 5000),
        Supervise(
          pingpong1,
          Permanent)
        :: Nil))
  }

  def getSingleActorOneForOneSupervisor: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start

    Supervisor(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, 5000),
        Supervise(
          pingpong1,
          Permanent)
        :: Nil))
  }

  def getMultipleActorsAllForOneConf: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start
    pingpong2 = actorOf[PingPong2Actor].start
    pingpong3 = actorOf[PingPong3Actor].start

    Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 5000),
        Supervise(
          pingpong1,
          Permanent)
        ::
        Supervise(
          pingpong2,
          Permanent)
        ::
        Supervise(
          pingpong3,
          Permanent)
        :: Nil))
  }

  def getMultipleActorsOneForOneConf: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start
    pingpong2 = actorOf[PingPong2Actor].start
    pingpong3 = actorOf[PingPong3Actor].start

    Supervisor(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, 5000),
        Supervise(
          pingpong1,
          Permanent)
        ::
        Supervise(
          pingpong2,
          Permanent)
        ::
        Supervise(
          pingpong3,
          Permanent)
        :: Nil))
  }

  def getNestedSupervisorsAllForOneConf: Supervisor = {
    pingpong1 = actorOf[PingPong1Actor].start
    pingpong2 = actorOf[PingPong2Actor].start
    pingpong3 = actorOf[PingPong3Actor].start

    Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 5000),
        Supervise(
          pingpong1,
          Permanent)
        ::
        SupervisorConfig(
          AllForOneStrategy(Nil, 3, 5000),
          Supervise(
            pingpong2,
            Permanent)
          ::
          Supervise(
            pingpong3,
            Permanent)
          :: Nil)
      :: Nil))
   }
}
