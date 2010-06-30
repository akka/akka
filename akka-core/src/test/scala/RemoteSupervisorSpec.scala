/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, BlockingQueue}
import se.scalablesolutions.akka.serialization.BinaryString
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.Config
import se.scalablesolutions.akka.remote.{RemoteNode, RemoteServer, RemoteClient}
import se.scalablesolutions.akka.OneWay
import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.junit.{Test, Before, After}

object Log {
  val messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  val oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }
}

@serializable class RemotePingPong1Actor extends Actor {
  def receive = {
    case BinaryString("Ping") =>
      Log.messageLog.put("ping")
      self.reply("pong")

    case OneWay =>
      Log.oneWayLog.put("oneway")

    case BinaryString("Die") =>
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }

  override def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

@serializable class RemotePingPong2Actor extends Actor {
  def receive = {
    case BinaryString("Ping") =>
      Log.messageLog.put("ping")
      self.reply("pong")
    case BinaryString("Die") =>
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }

  override def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

@serializable class RemotePingPong3Actor extends Actor {
  def receive = {
    case BinaryString("Ping") =>
      Log.messageLog.put("ping")
      self.reply("pong")
    case BinaryString("Die") =>
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }

  override def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

object RemoteSupervisorSpec {
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteSupervisorSpec extends JUnitSuite {
  import RemoteSupervisorSpec._

  var pingpong1: ActorRef = _
  var pingpong2: ActorRef = _
  var pingpong3: ActorRef = _

  import Log._

  @Before
  def init {
    server = new RemoteServer()
    server.start(HOSTNAME, PORT)
    Thread.sleep(1000)
  }

  @After
  def finished {
    try {
      server.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }

  @Test def shouldStartServer = {
    Log.messageLog.clear
    val sup = getSingleActorAllForOneSupervisor

    expect("pong") {
      (pingpong1 !! BinaryString("Ping")).getOrElse("nil")
    }
  }
  @Test def shouldStartServerForNestedSupervisorHierarchy = {
    clearMessageLogs
    val sup = getNestedSupervisorsAllForOneConf
    sup.start

    expect("pong") {
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }
  }

  @Test def shouldKillSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor

    intercept[RuntimeException] {
      pingpong1 !! (BinaryString("Die"), 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor

    expect("pong") {
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! (BinaryString("Die"), 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillSingleActorAllForOne = {
    clearMessageLogs
    val sup = getSingleActorAllForOneSupervisor

    intercept[RuntimeException] {
      pingpong1 !! (BinaryString("Die"), 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldCallKillCallSingleActorAllForOne = {
    clearMessageLogs
    val sup = getSingleActorAllForOneSupervisor

    expect("pong") {
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    intercept[RuntimeException] {
      pingpong1 !! (BinaryString("Die"), 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("ping") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  @Test def shouldKillMultipleActorsOneForOne1 = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf

    intercept[RuntimeException] {
      pingpong1 !! (BinaryString("Die"), 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }

  /*
  // Uncomment when the same test passes in SupervisorSpec - pending bug
  @Test def shouldKillMultipleActorsOneForOne2 = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf

    intercept[RuntimeException] {
      pingpong3 !! (BinaryString("Die"), 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }
*/
  @Test def shouldKillCallMultipleActorsOneForOne = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf

    expect("pong") {
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
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
      pingpong2 !! (BinaryString("Die"), 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
    expect("pong") {
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
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
      pingpong2 !! (BinaryString("Die"), 5000)
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
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
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
      pingpong2 !! (BinaryString("Die"), 5000)
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
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
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

  /*

  @Test def shouldOneWayKillSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor

    pingpong1 ! BinaryString("Die")

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
    pingpong1 ! BinaryString("Die")

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
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
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
      pingpong2 !! (BinaryString("Die"), 5000)
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
      (pingpong1 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong2 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
    }

    expect("pong") {
      (pingpong3 !! (BinaryString("Ping"), 5000)).getOrElse("nil")
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
   */
  // =============================================
  // Creat some supervisors with different configurations

  def getSingleActorAllForOneSupervisor: Supervisor = {

    // Create an abstract SupervisorContainer that works for all implementations
    // of the different Actors (Services).
    //
    // Then create a concrete container in which we mix in support for the specific
    // implementation of the Actors we want to use.

    pingpong1 = actorOf[RemotePingPong1Actor]
    pingpong1.makeRemote(HOSTNAME, PORT)
    pingpong1.start

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
    pingpong1 = actorOf[RemotePingPong1Actor]
    pingpong1.makeRemote(HOSTNAME, PORT)
    pingpong1.start

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
    pingpong1 = actorOf[RemotePingPong1Actor]
    pingpong1.makeRemote(HOSTNAME, PORT)
    pingpong1.start
    pingpong2 = actorOf[RemotePingPong2Actor]
    pingpong2.makeRemote(HOSTNAME, PORT)
    pingpong2.start
    pingpong3 = actorOf[RemotePingPong3Actor]
    pingpong3.makeRemote(HOSTNAME, PORT)
    pingpong3.start

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
    pingpong1 = actorOf[RemotePingPong1Actor]
    pingpong1.makeRemote(HOSTNAME, PORT)
    pingpong1 = actorOf[RemotePingPong1Actor]
    pingpong1.makeRemote(HOSTNAME, PORT)
    pingpong1.start
    pingpong2 = actorOf[RemotePingPong2Actor]
    pingpong2.makeRemote(HOSTNAME, PORT)
    pingpong2.start
    pingpong3 = actorOf[RemotePingPong3Actor]
    pingpong3.makeRemote(HOSTNAME, PORT)
    pingpong3.start

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
    pingpong1 = actorOf[RemotePingPong1Actor]
    pingpong1.makeRemote(HOSTNAME, PORT)
    pingpong1.start
    pingpong2 = actorOf[RemotePingPong2Actor]
    pingpong2.makeRemote(HOSTNAME, PORT)
    pingpong2.start
    pingpong3 = actorOf[RemotePingPong3Actor]
    pingpong3.makeRemote(HOSTNAME, PORT)
    pingpong3.start

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
