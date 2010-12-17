/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import org.scalatest._
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.actor.Actor._
import akka.remote.NettyRemoteSupport

object ServerInitiatedRemoteSessionActorSpec {

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

class ServerInitiatedRemoteSessionActorSpec extends AkkaRemoteTest {
  import ServerInitiatedRemoteSessionActorSpec._

  "A remote session Actor" should {
    "create a new session actor per connection" in {

      val session1 = remote.actorFor("untyped-session-actor-service", 5000L, host, port)

      val default1 = session1 !! GetUser()
      default1.as[String].get must equal ("anonymous")

      session1 ! Login("session[1]")
      val result1 = session1 !! GetUser()
      result1.as[String].get must equal ("session[1]")

      session1.stop

      remote.shutdownClientModule

      val session2 = remote.actorFor("untyped-session-actor-service", 5000L, host, port)

      // since this is a new session, the server should reset the state
      val default2 = session2 !! GetUser()
      default2.as[String].get must equal ("anonymous")

      session2.stop()
    }

    /*"stop the actor when the client disconnects" in {
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

    "stop the actor when there is an error" in {
      val session1 = RemoteClient.actorFor(
        "untyped-session-actor-service",
        5000L,
        HOSTNAME, PORT)


      session1 ! DoSomethingFunny()
      session1.stop()

      Thread.sleep(1000)

      instantiatedSessionActors should have size (0)
    }

    "be able to unregister" in {
      server.registerPerSession("my-service-1", actorOf[RemoteStatefullSessionActorSpec])
      server.actorsFactories.get("my-service-1") should not be (null)
      server.unregisterPerSession("my-service-1")
      server.actorsFactories.get("my-service-1") should be (null)
    } */
  }
}

