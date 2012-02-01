/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import foo.{ PingActor, IMyPojo, MyPojo }
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.ApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.core.io.{ ClassPathResource, Resource }
import org.scalatest.{ BeforeAndAfterAll, FeatureSpec }
import akka.remote.netty.NettyRemoteTransport
import akka.actor._
import akka.actor.Actor._
import java.util.concurrent.{TimeoutException, CountDownLatch}

object RemoteTypedActorLog {
  import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit, BlockingQueue }
  val messageLog: BlockingQueue[String] = new LinkedBlockingQueue[String]
  val oneWayLog = new LinkedBlockingQueue[String]

  def clearMessageLogs {
    messageLog.clear
    oneWayLog.clear
  }
}

/**
 * Tests for spring configuration of typed actors.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class TypedActorSpringFeatureTest extends FeatureSpec with ShouldMatchers with BeforeAndAfterAll {

  var optimizeLocal_? = remote.asInstanceOf[NettyRemoteTransport].optimizeLocalScoped_?

  override def beforeAll {
    remote.asInstanceOf[NettyRemoteTransport].optimizeLocal.set(false) //Can't run the test if we're eliminating all remote calls
    remote.start("localhost", 9990)
    val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
    remote.registerTypedActor("typed-actor-service", typedActor)
  }

  override def afterAll {
    remote.asInstanceOf[NettyRemoteTransport].optimizeLocal.set(optimizeLocal_?) //Reset optimizelocal after all tests

    remote.shutdown
    Thread.sleep(1000)
  }

  def getTypedActorFromContext(config: String, id: String): IMyPojo = {
    MyPojo.latch = new CountDownLatch(1)
    val context = new ClassPathXmlApplicationContext(config)
    val myPojo: IMyPojo = context.getBean(id).asInstanceOf[IMyPojo]
    myPojo
  }

  feature("parse Spring system context") {

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

    scenario("TimeoutException when timed out") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "simple-typed-actor")
      evaluating { myPojo.longRunning() } should produce[TimeoutException]
    }

    scenario("typed-actor with timeout") {
      val myPojo = getTypedActorFromContext("/typed-actor-config.xml", "simple-typed-actor-long-timeout")
      assert(myPojo.longRunning() === "this took long");
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
      val myPojoProxy = remote.typedActorFor(classOf[IMyPojo], classOf[IMyPojo].getName, 5000L, "localhost", 9990)
      assert(myPojoProxy.getFoo() === "foo")
      myPojoProxy.oneWay("hello server-managed-remote-typed-actor")
      MyPojo.latch.await
      assert(MyPojo.lastOneWayMessage === "hello server-managed-remote-typed-actor")
    }

    scenario("get a server-managed-remote-typed-actor-custom-id") {
      val serverPojo = getTypedActorFromContext("/server-managed-config.xml", "server-managed-remote-typed-actor-custom-id")
      //
      val myPojoProxy = remote.typedActorFor(classOf[IMyPojo], "mypojo-service", 5000L, "localhost", 9990)
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

