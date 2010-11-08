/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.spring


import foo.{PingActor, IMyPojo, MyPojo}
import akka.dispatch.FutureTimeoutException
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.ApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.core.io.{ClassPathResource, Resource}
import org.scalatest.{BeforeAndAfterAll, FeatureSpec}
import akka.remote.{RemoteClient, RemoteServer, RemoteNode}
import java.util.concurrent.CountDownLatch
import akka.actor.{TypedActor, RemoteTypedActorOne, Actor}
import akka.actor.remote.RemoteTypedActorOneImpl

/**
 * Tests for spring configuration of typed actors.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class TypedActorSpringFeatureTest extends FeatureSpec with ShouldMatchers with BeforeAndAfterAll {

  var server1: RemoteServer = null
  var server2: RemoteServer = null

  override def beforeAll = {
    val actor = Actor.actorOf[PingActor] // FIXME: remove this line when ticket 425 is fixed
    server1 = new RemoteServer()
    server1.start("localhost", 9990)
    server2 = new RemoteServer()
    server2.start("localhost", 9992)

    val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
    server1.registerTypedActor("typed-actor-service", typedActor)
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

  def getTypedActorFromContext(config: String, id: String) : IMyPojo = {
    MyPojo.latch = new CountDownLatch(1)
    val context = new ClassPathXmlApplicationContext(config)
    val myPojo: IMyPojo = context.getBean(id).asInstanceOf[IMyPojo]
    myPojo
  }

  feature("parse Spring application context") {

    scenario("akka:typed-actor and akka:supervision and akka:dispatcher can be used as top level elements") {
      val context = new ClassPathResource("/typed-actor-config.xml")
      val beanFactory = new DefaultListableBeanFactory()
      val reader = new XmlBeanDefinitionReader(beanFactory)
      reader.loadBeanDefinitions(context)
      assert(beanFactory.containsBeanDefinition("simple-typed-actor"))
      assert(beanFactory.containsBeanDefinition("remote-typed-actor"))
      assert(beanFactory.containsBeanDefinition("supervision1"))
      assert(beanFactory.containsBeanDefinition("dispatcher1"))
    }

    scenario("get a typed actor") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "simple-typed-actor")
      assert(myPojo.getFoo() === "foo")
      myPojo.oneWay("hello 1")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello 1")
    }

    scenario("get a typed actor of bean") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "simple-typed-actor-of-bean")
      assert(myPojo.getFoo() === "foo")
      myPojo.oneWay("hello 1")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello 1")
    }

    scenario("FutureTimeoutException when timed out") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "simple-typed-actor")
      evaluating {myPojo.longRunning()} should produce[FutureTimeoutException]
    }

    scenario("typed-actor with timeout") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "simple-typed-actor-long-timeout")
      assert(myPojo.longRunning() === "this took long");
    }

    scenario("transactional typed-actor") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "transactional-typed-actor")
      assert(myPojo.getFoo() === "foo")
      myPojo.oneWay("hello 2")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello 2")
    }

    scenario("get a remote typed-actor") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "remote-typed-actor")
      assert(myPojo.getFoo() === "foo")
      myPojo.oneWay("hello 3")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello 3")
    }

    scenario("get a client-managed-remote-typed-actor") {
      val myPojo = getTypedActorFromContext("/server-managed-config.xml", "client-managed-remote-typed-actor")
      assert(myPojo.getFoo() === "foo")
      myPojo.oneWay("hello client-managed-remote-typed-actor")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello client-managed-remote-typed-actor")
    }

    scenario("get a server-managed-remote-typed-actor") {
      val serverPojo = getTypedActorFromContext("/server-managed-config.xml", "server-managed-remote-typed-actor")
      //
      val myPojoProxy = RemoteClient.typedActorFor(classOf[IMyPojo], classOf[IMyPojo].getName, 5000L, "localhost", 9990)
      assert(myPojoProxy.getFoo() === "foo")
      myPojoProxy.oneWay("hello server-managed-remote-typed-actor")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello server-managed-remote-typed-actor")
    }

    scenario("get a server-managed-remote-typed-actor-custom-id") {
      val serverPojo = getTypedActorFromContext("/server-managed-config.xml", "server-managed-remote-typed-actor-custom-id")
      //
      val myPojoProxy = RemoteClient.typedActorFor(classOf[IMyPojo], "mypojo-service", 5000L, "localhost", 9990)
      assert(myPojoProxy.getFoo() === "foo")
      myPojoProxy.oneWay("hello server-managed-remote-typed-actor 2")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello server-managed-remote-typed-actor 2")
    }

    scenario("get a client proxy for server-managed-remote-typed-actor") {
      MyPojo.latch = new CountDownLatch(1)
      val context = new ClassPathXmlApplicationContext("/server-managed-config.xml")
      val myPojo: IMyPojo = context.getBean("server-managed-remote-typed-actor-custom-id").asInstanceOf[IMyPojo]
      // get client proxy from spring context
      val myPojoProxy = context.getBean("typed-client-1").asInstanceOf[IMyPojo]
      assert(myPojoProxy.getFoo() === "foo")
      myPojoProxy.oneWay("hello")
      MyPojo.latch.await
    }


  }

}

