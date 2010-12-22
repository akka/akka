/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.config.Supervision._
import akka.actor._
import akka.remote.{RemoteServer, RemoteClient}

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, BlockingQueue}
import org.scalatest.{BeforeAndAfterEach, Spec, Assertions, BeforeAndAfterAll}
import akka.config.{Config, TypedActorConfigurator, RemoteAddress}

object RemoteTypedActorSpec {
  val HOSTNAME = "localhost"
  val PORT = 9988
  var server: RemoteServer = null
}

object RemoteTypedActorLog {
  val messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  val oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }
}

@RunWith(classOf[JUnitRunner])
class RemoteTypedActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterEach with BeforeAndAfterAll {

  import RemoteTypedActorLog._
  import RemoteTypedActorSpec._

  private val conf = new TypedActorConfigurator

  override def beforeAll = {
    server = new RemoteServer()
    server.start("localhost", 9995)
    Config.config
    conf.configure(
      new AllForOneStrategy(List(classOf[Exception]), 3, 5000),
      List(
        new SuperviseTypedActor(
          classOf[RemoteTypedActorOne],
          classOf[RemoteTypedActorOneImpl],
          Permanent,
          10000,
          new RemoteAddress("localhost", 9995)),
        new SuperviseTypedActor(
          classOf[RemoteTypedActorTwo],
          classOf[RemoteTypedActorTwoImpl],
          Permanent,
          10000,
          new RemoteAddress("localhost", 9995))
      ).toArray).supervise
    Thread.sleep(1000)
  }

  override def afterAll = {
    conf.stop
    try {
      server.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
    ActorRegistry.shutdownAll
  }

   override def afterEach() {
    server.typedActors.clear
  }

  describe("Remote Typed Actor ") {

    it("should receive one-way message") {
      clearMessageLogs
      val ta = conf.getInstance(classOf[RemoteTypedActorOne])

      expect("oneway") {
        ta.oneWay
        oneWayLog.poll(5, TimeUnit.SECONDS)
      }
    }

    it("should respond to request-reply message") {
      clearMessageLogs
      val ta = conf.getInstance(classOf[RemoteTypedActorOne])

      expect("pong") {
        ta.requestReply("ping")
      }
    }

    it("should be restarted on failure") {
      clearMessageLogs
      val ta = conf.getInstance(classOf[RemoteTypedActorOne])

      intercept[RuntimeException] {
        ta.requestReply("die")
      }
      messageLog.poll(5, TimeUnit.SECONDS) should equal ("Expected exception; to test fault-tolerance")
    }

    it("should restart linked friends on failure") {
      clearMessageLogs
      val ta1 = conf.getInstance(classOf[RemoteTypedActorOne])
      val ta2 = conf.getInstance(classOf[RemoteTypedActorTwo])

      intercept[RuntimeException] {
        ta1.requestReply("die")
      }
      messageLog.poll(5, TimeUnit.SECONDS) should equal ("Expected exception; to test fault-tolerance")
      messageLog.poll(5, TimeUnit.SECONDS) should equal ("Expected exception; to test fault-tolerance")
    }
  }
}
