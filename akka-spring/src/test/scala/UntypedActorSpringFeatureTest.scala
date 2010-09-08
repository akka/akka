/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring


import foo.PingActor
import se.scalablesolutions.akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher
import se.scalablesolutions.akka.remote.RemoteNode
import se.scalablesolutions.akka.actor.ActorRef
import org.scalatest.FeatureSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.context.ApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext


/**
 * Tests for spring configuration of typed actors.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class UntypedActorSpringFeatureTest extends FeatureSpec with ShouldMatchers {
  feature("parse Spring application context") {

    scenario("get an untyped actor") {
      val context = new ClassPathXmlApplicationContext("/untyped-actor-config.xml")
      val myactor = context.getBean("simple-untyped-actor").asInstanceOf[ActorRef]
      assert(myactor.getActorClassName() === "se.scalablesolutions.akka.spring.foo.PingActor")
      myactor.start()
      myactor.sendOneWay("Hello")
      assert(myactor.isDefinedAt("some string message"))
    }

    scenario("untyped-actor with timeout") {
      val context = new ClassPathXmlApplicationContext("/untyped-actor-config.xml")
      val myactor = context.getBean("simple-untyped-actor-long-timeout").asInstanceOf[ActorRef]
      assert(myactor.getActorClassName() === "se.scalablesolutions.akka.spring.foo.PingActor")
      myactor.start()
      myactor.sendOneWay("Hello")
      assert(myactor.getTimeout() === 10000)
    }

    scenario("transactional untyped-actor") {
      val context = new ClassPathXmlApplicationContext("/untyped-actor-config.xml")
      val myactor = context.getBean("transactional-untyped-actor").asInstanceOf[ActorRef]
      assert(myactor.getActorClassName() === "se.scalablesolutions.akka.spring.foo.PingActor")
      myactor.start()
      myactor.sendOneWay("Hello")
      assert(myactor.isDefinedAt("some string message"))
    }

    scenario("get a remote typed-actor") {
      RemoteNode.start
      Thread.sleep(1000)
      val context = new ClassPathXmlApplicationContext("/untyped-actor-config.xml")
      val myactor = context.getBean("remote-untyped-actor").asInstanceOf[ActorRef]
      assert(myactor.getActorClassName() === "se.scalablesolutions.akka.spring.foo.PingActor")
      myactor.start()
      myactor.sendOneWay("Hello")
      assert(myactor.isDefinedAt("some string message"))
      assert(myactor.getRemoteAddress().isDefined)
      assert(myactor.getRemoteAddress().get.getHostName() === "localhost")
      assert(myactor.getRemoteAddress().get.getPort() === 9999)
    }

    scenario("untyped-actor with custom dispatcher") {
      val context = new ClassPathXmlApplicationContext("/untyped-actor-config.xml")
      val myactor = context.getBean("untyped-actor-with-dispatcher").asInstanceOf[ActorRef]
      assert(myactor.getActorClassName() === "se.scalablesolutions.akka.spring.foo.PingActor")
      myactor.start()
      myactor.sendOneWay("Hello")
      assert(myactor.getTimeout() === 1000)
      assert(myactor.getDispatcher.isInstanceOf[ExecutorBasedEventDrivenWorkStealingDispatcher])
    }
  }
}

