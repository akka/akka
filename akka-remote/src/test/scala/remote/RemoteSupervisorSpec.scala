/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit, BlockingQueue }
import akka.config.Supervision._
import akka.OneWay
import org.scalatest._
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.{ SupervisorFactory, Supervisor, ActorRef, Actor }
import Actor._

object Log {
  val messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  val oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }
}

class RemotePingPong1Actor extends Actor with Serializable {
  def receive = {
    case "Ping" ⇒
      Log.messageLog.put("ping")
      self.reply("pong")

    case OneWay ⇒
      Log.oneWayLog.put("oneway")

    case "Die" ⇒
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }

  override def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

class RemotePingPong2Actor extends Actor with Serializable {
  def receive = {
    case "Ping" ⇒
      Log.messageLog.put("ping")
      self.reply("pong")
    case "Die" ⇒
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }

  override def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

class RemotePingPong3Actor extends Actor with Serializable {
  def receive = {
    case "Ping" ⇒
      Log.messageLog.put("ping")
      self.reply("pong")
    case "Die" ⇒
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }

  override def postRestart(reason: Throwable) {
    Log.messageLog.put(reason.getMessage)
  }
}

class RemoteSupervisorSpec extends AkkaRemoteTest {

  var pingpong1: ActorRef = _
  var pingpong2: ActorRef = _
  var pingpong3: ActorRef = _

  import Log._

  "Remote supervision" should {

    "start server" in {
      Log.messageLog.clear
      val sup = getSingleActorAllForOneSupervisor

      (pingpong1 !! "Ping") must equal(Some("pong"))
    }

    "StartServerForNestedSupervisorHierarchy" in {
      clearMessageLogs
      val sup = getNestedSupervisorsAllForOneConf
      sup.start

      (pingpong1 !! ("Ping", 5000)) must equal(Some("pong"))
    }

    "killSingleActorOneForOne" in {
      clearMessageLogs
      val sup = getSingleActorOneForOneSupervisor

      (pingpong1 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
    }

    "callKillCallSingleActorOneForOne" in {
      clearMessageLogs
      val sup = getSingleActorOneForOneSupervisor

      (pingpong1 !! ("Ping", 5000)) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")

      (pingpong1 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
      (pingpong1 !! ("Ping", 5000)) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
    }

    "KillSingleActorAllForOne" in {
      clearMessageLogs
      val sup = getSingleActorAllForOneSupervisor

      (pingpong1 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
    }

    "CallKillCallSingleActorAllForOne" in {
      clearMessageLogs
      val sup = getSingleActorAllForOneSupervisor

      (pingpong1 !! ("Ping", 5000)) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")

      (pingpong1 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")

      (pingpong1 !! ("Ping", 5000)) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
    }

    "KillMultipleActorsOneForOne1" in {
      clearMessageLogs
      val sup = getMultipleActorsOneForOneConf

      (pingpong1 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
    }

    "KillCallMultipleActorsOneForOne" in {
      clearMessageLogs
      val sup = getMultipleActorsOneForOneConf

      (pingpong1 !! ("Ping", 5000)) must equal(Some("pong"))
      (pingpong2 !! ("Ping", 5000)) must equal(Some("pong"))
      (pingpong3 !! ("Ping", 5000)) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")

      (pingpong2 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")

      (pingpong1 !! ("Ping", 5000)) must equal(Some("pong"))
      (pingpong2 !! ("Ping", 5000)) must equal(Some("pong"))
      (pingpong3 !! ("Ping", 5000)) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
    }

    "KillMultipleActorsAllForOne" in {
      clearMessageLogs
      val sup = getMultipleActorsAllForOneConf

      (pingpong2 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
    }

    "CallKillCallMultipleActorsAllForOne" in {
      clearMessageLogs
      val sup = getMultipleActorsAllForOneConf

      pingpong1 !! ("Ping", 5000) must equal(Some("pong"))
      pingpong2 !! ("Ping", 5000) must equal(Some("pong"))
      pingpong3 !! ("Ping", 5000) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")

      (pingpong2 !!! ("Die", 5000)).await.exception.isDefined must be(true)

      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")

      pingpong1 !! ("Ping", 5000) must equal(Some("pong"))
      pingpong2 !! ("Ping", 5000) must equal(Some("pong"))
      pingpong3 !! ("Ping", 5000) must equal(Some("pong"))

      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
      messageLog.poll(5, TimeUnit.SECONDS) must equal("ping")
    }
  }

  def getSingleActorAllForOneSupervisor: Supervisor = {

    // Create an abstract SupervisorContainer that works for all implementations
    // of the different Actors (Services).
    //
    // Then create a concrete container in which we mix in support for the specific
    // implementation of the Actors we want to use.

    pingpong1 = remote.actorOf[RemotePingPong1Actor](host, port).start()

    val factory = SupervisorFactory(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 100),
        Supervise(
          pingpong1,
          Permanent)
          :: Nil))

    factory.newInstance
  }

  def getSingleActorOneForOneSupervisor: Supervisor = {
    pingpong1 = remote.actorOf[RemotePingPong1Actor](host, port).start()

    val factory = SupervisorFactory(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, 100),
        Supervise(
          pingpong1,
          Permanent)
          :: Nil))
    factory.newInstance
  }

  def getMultipleActorsAllForOneConf: Supervisor = {
    pingpong1 = remote.actorOf[RemotePingPong1Actor](host, port).start()
    pingpong2 = remote.actorOf[RemotePingPong2Actor](host, port).start()
    pingpong3 = remote.actorOf[RemotePingPong3Actor](host, port).start()

    val factory = SupervisorFactory(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 100),
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
    factory.newInstance
  }

  def getMultipleActorsOneForOneConf: Supervisor = {
    pingpong1 = remote.actorOf[RemotePingPong1Actor](host, port).start()
    pingpong2 = remote.actorOf[RemotePingPong2Actor](host, port).start()
    pingpong3 = remote.actorOf[RemotePingPong3Actor](host, port).start()

    val factory = SupervisorFactory(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, 100),
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
    factory.newInstance
  }

  def getNestedSupervisorsAllForOneConf: Supervisor = {
    pingpong1 = remote.actorOf[RemotePingPong1Actor](host, port).start()
    pingpong2 = remote.actorOf[RemotePingPong2Actor](host, port).start()
    pingpong3 = remote.actorOf[RemotePingPong3Actor](host, port).start()

    val factory = SupervisorFactory(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 100),
        Supervise(
          pingpong1,
          Permanent)
          ::
          SupervisorConfig(
            AllForOneStrategy(List(classOf[Exception]), 3, 100),
            Supervise(
              pingpong2,
              Permanent)
              ::
              Supervise(
                pingpong3,
                Permanent)
                :: Nil)
            :: Nil))
    factory.newInstance
  }

  /*
  // Uncomment when the same test passes in SupervisorSpec - pending bug
  @Test def shouldKillMultipleActorsOneForOne2 = {
    clearMessageLogs
    val sup = getMultipleActorsOneForOneConf

    intercept[RuntimeException] {
      pingpong3 !! ("Die", 5000)
    }

    expect("Expected exception; to test fault-tolerance") {
      messageLog.poll(5, TimeUnit.SECONDS)
    }
  }
*/

  /*

  @Test def shouldOneWayKillSingleActorOneForOne = {
    clearMessageLogs
    val sup = getSingleActorOneForOneSupervisor

    pingpong1 ! "Die"

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
    pingpong1 ! "Die"

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
      (pingpong1 !! ("Ping", 5000)) must equal (Some("pong"))
    }

    expect("pong") {
      (pingpong2 !! ("Ping", 5000)) must equal (Some("pong"))
    }

    expect("pong") {
      (pingpong3 !! ("Ping", 5000)) must equal (Some("pong"))
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
      pingpong2 !! ("Die", 5000)
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
      (pingpong1 !! ("Ping", 5000)) must equal (Some("pong"))
    }

    expect("pong") {
      (pingpong2 !! ("Ping", 5000)) must equal (Some("pong"))
    }

    expect("pong") {
      (pingpong3 !! ("Ping", 5000)) must equal (Some("pong"))
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

}
