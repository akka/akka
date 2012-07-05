/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import akka.config.Supervision._
import akka.actor._

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit, BlockingQueue }
import akka.config.{ RemoteAddress, Config, TypedActorConfigurator }

import akka.testkit.Testing

object RemoteTypedActorLog {
  val messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  val oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }
}

class RemoteTypedActorSpec extends AkkaRemoteTest {

  import RemoteTypedActorLog._

  private var conf: TypedActorConfigurator = _

  override def beforeEach {
    super.beforeEach
    Config.config
    conf = new TypedActorConfigurator
    conf.configure(
      new AllForOneStrategy(List(classOf[Exception]), 3, 5000),
      List(
        new SuperviseTypedActor(
          classOf[RemoteTypedActorOne],
          classOf[RemoteTypedActorOneImpl],
          Permanent,
          Testing.testTime(20000),
          RemoteAddress(host, port)),
        new SuperviseTypedActor(
          classOf[RemoteTypedActorTwo],
          classOf[RemoteTypedActorTwoImpl],
          Permanent,
          Testing.testTime(20000),
          RemoteAddress(host, port))).toArray).supervise
  }

  override def afterEach {
    clearMessageLogs
    conf.stop
    super.afterEach
    Thread.sleep(1000)
  }

  "Remote Typed Actor " should {

    /*"receives one-way message" in {
      val ta = conf.getInstance(classOf[RemoteTypedActorOne])

      ta.oneWay
      oneWayLog.poll(5, TimeUnit.SECONDS) must equal ("oneway")
    }

    "responds to request-reply message" in {
      val ta = conf.getInstance(classOf[RemoteTypedActorOne])
      ta.requestReply("ping") must equal ("pong")
    } */

    "be restarted on failure" in {
      val ta = conf.getInstance(classOf[RemoteTypedActorOne])

      try {
        ta.requestReply("die")
        fail("Shouldn't get here")
      } catch { case re: RuntimeException if re.getMessage == "Expected exception; to test fault-tolerance" ⇒ }
      messageLog.poll(5, TimeUnit.SECONDS) must equal("Expected exception; to test fault-tolerance")
    }

    /* "restarts linked friends on failure" in {
      val ta1 = conf.getInstance(classOf[RemoteTypedActorOne])
      val ta2 = conf.getInstance(classOf[RemoteTypedActorTwo])

      try {
        ta1.requestReply("die")
        fail("Shouldn't get here")
      } catch { case re: RuntimeException if re.getMessage == "Expected exception; to test fault-tolerance" => }
      messageLog.poll(5, TimeUnit.SECONDS) must equal ("Expected exception; to test fault-tolerance")
      messageLog.poll(5, TimeUnit.SECONDS) must equal ("Expected exception; to test fault-tolerance")
    }*/
  }
}
