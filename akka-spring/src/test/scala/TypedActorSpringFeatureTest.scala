/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring


import foo.{IMyPojo, MyPojo}
import se.scalablesolutions.akka.dispatch.FutureTimeoutException
import se.scalablesolutions.akka.remote.RemoteNode
import org.scalatest.FeatureSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.ApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.core.io.{ClassPathResource, Resource}

/**
 * Tests for spring configuration of typed actors.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class TypedActorSpringFeatureTest extends FeatureSpec with ShouldMatchers {
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
      val context = new ClassPathXmlApplicationContext("/typed-actor-config.xml")
      val myPojo = context.getBean("simple-typed-actor").asInstanceOf[IMyPojo]
      var msg = myPojo.getFoo()
      msg += myPojo.getBar()
      assert(msg === "foobar")
    }

    scenario("FutureTimeoutException when timed out") {
      val context = new ClassPathXmlApplicationContext("/typed-actor-config.xml")
      val myPojo = context.getBean("simple-typed-actor").asInstanceOf[IMyPojo]
      evaluating {myPojo.longRunning()} should produce[FutureTimeoutException]

    }

    scenario("typed-actor with timeout") {
      val context = new ClassPathXmlApplicationContext("/typed-actor-config.xml")
      val myPojo = context.getBean("simple-typed-actor-long-timeout").asInstanceOf[IMyPojo]
      assert(myPojo.longRunning() === "this took long");
    }

    scenario("transactional typed-actor") {
      val context = new ClassPathXmlApplicationContext("/typed-actor-config.xml")
      val myPojo = context.getBean("transactional-typed-actor").asInstanceOf[IMyPojo]
      var msg = myPojo.getFoo()
      msg += myPojo.getBar()
      assert(msg === "foobar")
    }

    scenario("get a remote typed-actor") {
      RemoteNode.start
      Thread.sleep(1000)
      val context = new ClassPathXmlApplicationContext("/typed-actor-config.xml")
      val myPojo = context.getBean("remote-typed-actor").asInstanceOf[IMyPojo]
      assert(myPojo.getFoo === "foo")
    }
  }

}

