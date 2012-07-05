/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import akka.actor._
import akka.actor.Actor._
import java.util.concurrent.{ ConcurrentSkipListSet, TimeUnit }
import akka.remote.netty.NettyRemoteSupport

object ServerInitiatedRemoteSessionActorSpec {

  case class Login(user: String)
  case class GetUser()
  case class DoSomethingFunny()

  val instantiatedSessionActors = new ConcurrentSkipListSet[ActorRef]()

  class RemoteStatefullSessionActorSpec extends Actor {

    override def preStart() = instantiatedSessionActors.add(self)
    override def postStop() = instantiatedSessionActors.remove(self)
    var user: String = "anonymous"

    def receive = {
      case Login(user)        ⇒ this.user = user
      case GetUser()          ⇒ self.reply(this.user)
      case DoSomethingFunny() ⇒ throw new Exception("Bad boy")
    }
  }

}

class ServerInitiatedRemoteSessionActorSpec extends AkkaRemoteTest {
  import ServerInitiatedRemoteSessionActorSpec._

  "A remote session Actor" should {
    "create a new session actor per connection" in {
      remote.registerPerSession("untyped-session-actor-service", actorOf[RemoteStatefullSessionActorSpec])

      val session1 = remote.actorFor("untyped-session-actor-service", 5000L, host, port)

      val default1 = session1 !! GetUser()
      default1.as[String] must equal(Some("anonymous"))

      session1 ! Login("session[1]")
      val result1 = session1 !! GetUser()
      result1.as[String] must equal(Some("session[1]"))

      remote.shutdownClientModule()

      val session2 = remote.actorFor("untyped-session-actor-service", 5000L, host, port)

      // since this is a new session, the server should reset the state
      val default2 = session2 !! GetUser()
      default2.as[String] must equal(Some("anonymous"))
    }

    "stop the actor when the client disconnects" in {
      instantiatedSessionActors.clear
      remote.registerPerSession("untyped-session-actor-service", actorOf[RemoteStatefullSessionActorSpec])
      val session1 = remote.actorFor("untyped-session-actor-service", 5000L, host, port)

      val default1 = session1 !! GetUser()
      default1.as[String] must equal(Some("anonymous"))

      instantiatedSessionActors must have size (1)
      remote.shutdownClientModule()
      Thread.sleep(1000)
      instantiatedSessionActors must have size (0)
    }

    "stop the actor when there is an error" in {
      instantiatedSessionActors.clear
      remote.registerPerSession("untyped-session-actor-service", actorOf[RemoteStatefullSessionActorSpec])
      val session1 = remote.actorFor("untyped-session-actor-service", 5000L, host, port)

      session1 ! DoSomethingFunny()
      session1.stop()
      Thread.sleep(1000)

      instantiatedSessionActors must have size (0)
    }

    "be able to unregister" in {
      remote.registerPerSession("my-service-1", actorOf[RemoteStatefullSessionActorSpec])
      remote.asInstanceOf[NettyRemoteSupport].actorsFactories.get("my-service-1") must not be (null)
      remote.unregisterPerSession("my-service-1")
      remote.asInstanceOf[NettyRemoteSupport].actorsFactories.get("my-service-1") must be(null)
    }
  }
}

