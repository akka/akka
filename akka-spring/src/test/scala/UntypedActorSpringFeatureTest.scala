/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.spring


import foo.PingActor
import akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.context.support.ClassPathXmlApplicationContext
import akka.remote.{RemoteClient, RemoteServer}
import org.scalatest.{BeforeAndAfterAll, FeatureSpec}

import java.util.concurrent.CountDownLatch
import akka.actor.{RemoteActorRef, ActorRegistry, Actor, ActorRef}

/**
 * Tests for spring configuration of typed actors.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class UntypedActorSpringFeatureTest extends FeatureSpec with ShouldMatchers with BeforeAndAfterAll {

  var server1: RemoteServer = null
  var server2: RemoteServer = null


  override def beforeAll = {
    val actor = Actor.actorOf[PingActor]  // FIXME: remove this line when ticket 425 is fixed
    server1 = new RemoteServer()
    server1.start("localhost", 9990)
    server2 = new RemoteServer()
    server2.start("localhost", 9992)
  }

  // make sure the servers shutdown cleanly after the test has finished
  override def afterAll = {
    try {
      server1.shutdown
      server2.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  } 


  def getPingActorFromContext(config: String, id: String) : ActorRef = {
    PingActor.latch = new CountDownLatch(1)
    val context = new ClassPathXmlApplicationContext(config)
    val pingActor = context.getBean(id).asInstanceOf[ActorRef]
    assert(pingActor.getActorClassName() === "akka.spring.foo.PingActor")
    pingActor.start()
  }


  feature("parse Spring application context") {

    scenario("get a untyped actor") {
      val myactor = getPingActorFromContext("/untyped-actor-config.xml", "simple-untyped-actor")
      myactor.sendOneWay("Hello")
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello")
      assert(myactor.isDefinedAt("some string message"))
    }

    scenario("untyped-actor with timeout") {
      val myactor = getPingActorFromContext("/untyped-actor-config.xml", "simple-untyped-actor-long-timeout")
      assert(myactor.getTimeout() === 10000)
      myactor.sendOneWay("Hello 2")
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello 2")
    }

    scenario("transactional untyped-actor") {
      val myactor = getPingActorFromContext("/untyped-actor-config.xml", "transactional-untyped-actor")
      myactor.sendOneWay("Hello 3")
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello 3")
    }

    scenario("get a remote typed-actor") {
      val myactor = getPingActorFromContext("/untyped-actor-config.xml", "remote-untyped-actor")
      myactor.sendOneWay("Hello 4")
      assert(myactor.getRemoteAddress().isDefined)
      assert(myactor.getRemoteAddress().get.getHostName() === "localhost")
      assert(myactor.getRemoteAddress().get.getPort() === 9992)
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello 4")
    }

    scenario("untyped-actor with custom dispatcher") {
      val myactor = getPingActorFromContext("/untyped-actor-config.xml", "untyped-actor-with-dispatcher")
      assert(myactor.getTimeout() === 1000)
      assert(myactor.getDispatcher.isInstanceOf[ExecutorBasedEventDrivenWorkStealingDispatcher])
      myactor.sendOneWay("Hello 5")
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello 5")
    }

    scenario("create client managed remote untyped-actor") {
      val myactor = getPingActorFromContext("/server-managed-config.xml", "client-managed-remote-untyped-actor")
      myactor.sendOneWay("Hello client managed remote untyped-actor")
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello client managed remote untyped-actor")
      assert(myactor.getRemoteAddress().isDefined)
      assert(myactor.getRemoteAddress().get.getHostName() === "localhost")
      assert(myactor.getRemoteAddress().get.getPort() === 9990)
    }

    scenario("create server managed remote untyped-actor") {
      val myactor = getPingActorFromContext("/server-managed-config.xml", "server-managed-remote-untyped-actor")
      val nrOfActors = ActorRegistry.actors.length
      val actorRef = RemoteClient.actorFor("akka.spring.foo.PingActor", "localhost", 9990)
      actorRef.sendOneWay("Hello server managed remote untyped-actor")
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello server managed remote untyped-actor")
      assert(ActorRegistry.actors.length === nrOfActors)
    }

    scenario("create server managed remote untyped-actor with custom service id") {
      val myactor = getPingActorFromContext("/server-managed-config.xml", "server-managed-remote-untyped-actor-custom-id")
      val nrOfActors = ActorRegistry.actors.length
      val actorRef = RemoteClient.actorFor("ping-service", "localhost", 9990)
      actorRef.sendOneWay("Hello server managed remote untyped-actor")
      PingActor.latch.await
      assert(PingActor.lastMessage === "Hello server managed remote untyped-actor")
      assert(ActorRegistry.actors.length === nrOfActors)
    }

    scenario("get client actor for server managed remote untyped-actor") {
      PingActor.latch = new CountDownLatch(1)
      val context = new ClassPathXmlApplicationContext("/server-managed-config.xml")
      val pingActor = context.getBean("server-managed-remote-untyped-actor-custom-id").asInstanceOf[ActorRef]
      assert(pingActor.getActorClassName() === "akka.spring.foo.PingActor")
      pingActor.start()
      val nrOfActors = ActorRegistry.actors.length
      // get client actor ref from spring context
      val actorRef = context.getBean("client-1").asInstanceOf[ActorRef]
      assert(actorRef.isInstanceOf[RemoteActorRef])
      actorRef.sendOneWay("Hello")
      PingActor.latch.await
      assert(ActorRegistry.actors.length === nrOfActors)
    }

  }
}

