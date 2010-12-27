/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import org.scalatest._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import java.util.concurrent.TimeUnit

import akka.remote.{RemoteServer, RemoteClient}
import akka.actor._
import akka.actor.Actor._
import RemoteTypedActorLog._

object ServerInitiatedRemoteSessionActorSpec {
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null

  case class Login(user:String)
  case class GetUser()
  case class DoSomethingFunny()

  var instantiatedSessionActors= Set[ActorRef]()

  class RemoteStatefullSessionActorSpec extends Actor {

    var user : String= "anonymous"

    override def preStart = {
      instantiatedSessionActors += self
    }

    override def postStop = {
      instantiatedSessionActors -= self
    }

    def receive = {
      case Login(user) =>
              this.user = user
      case GetUser() =>
        self.reply(this.user)
      case DoSomethingFunny() =>
        throw new Exception("Bad boy")
    }
  }

}

@RunWith(classOf[JUnitRunner])
class ServerInitiatedRemoteSessionActorSpec extends
  FlatSpec with
  ShouldMatchers with
  BeforeAndAfterEach  {
  import ServerInitiatedRemoteSessionActorSpec._

  private val unit = TimeUnit.MILLISECONDS


  override def beforeEach = {
    server = new RemoteServer()
    server.start(HOSTNAME, PORT)

    server.registerPerSession("untyped-session-actor-service", actorOf[RemoteStatefullSessionActorSpec])

    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  override def afterEach = {
    try {
      server.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }

  "A remote session Actor" should "create a new session actor per connection" in {
    clearMessageLogs

    val session1 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)

    val default1 = session1 !! GetUser()
    default1.get.asInstanceOf[String] should equal ("anonymous")
    session1 ! Login("session[1]")
    val result1 = session1 !! GetUser()
    result1.get.asInstanceOf[String] should equal ("session[1]")

    session1.stop()

    RemoteClient.shutdownAll

    //RemoteClient.clientFor(HOSTNAME, PORT).connect

    val session2 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)

    // since this is a new session, the server should reset the state
    val default2 = session2 !! GetUser()
    default2.get.asInstanceOf[String] should equal ("anonymous")

    session2.stop()

  }

  it should "stop the actor when the client disconnects" in {

    val session1 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)


    val default1 = session1 !! GetUser()
    default1.get.asInstanceOf[String] should equal ("anonymous")

    instantiatedSessionActors should have size (1)

    RemoteClient.shutdownAll
    Thread.sleep(1000)
    instantiatedSessionActors should have size (0)

  }

  it should "stop the actor when there is an error" in {

    val session1 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)


    session1 ! DoSomethingFunny()
    session1.stop()

    Thread.sleep(1000)

    instantiatedSessionActors should have size (0)
  }


  it should "be able to unregister" in {
      server.registerPerSession("my-service-1", actorOf[RemoteStatefullSessionActorSpec])
      server.actorsFactories.get("my-service-1") should not be (null)
      server.unregisterPerSession("my-service-1")
      server.actorsFactories.get("my-service-1") should be (null)
  }

}

