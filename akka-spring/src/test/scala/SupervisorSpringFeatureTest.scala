/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring


import se.scalablesolutions.akka.spring.foo.{IMyPojo, MyPojo, IFoo, IBar}
import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.TypedActorConfigurator
import se.scalablesolutions.akka.actor.Supervisor

import org.scalatest.FeatureSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.ApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.core.io.{ClassPathResource, Resource}
import java.util.concurrent._

/**
 * Tests for spring configuration of supervisor hierarchies.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class SupervisorSpringFeatureTest extends FeatureSpec with ShouldMatchers {
  
  feature("Spring configuration") {

    scenario("get a supervisor for typed actors from context") {
      val context = new ClassPathXmlApplicationContext("/supervisor-config.xml")
      val myConfigurator = context.getBean("supervision1").asInstanceOf[TypedActorConfigurator]
      // get TypedActors
      val foo = myConfigurator.getInstance(classOf[IFoo])
      assert(foo != null)
      val bar = myConfigurator.getInstance(classOf[IBar])
      assert(bar != null)
      val pojo = myConfigurator.getInstance(classOf[IMyPojo])
      assert(pojo != null)
    }

    scenario("get a supervisor for untyped actors from context") {
      val context = new ClassPathXmlApplicationContext("/supervisor-config.xml")
      val supervisor = context.getBean("supervision-untyped-actors").asInstanceOf[Supervisor]
      supervisor.children
    }

    scenario("get a supervisor and dispatcher from context") {
      val context = new ClassPathXmlApplicationContext("/supervisor-config.xml")
      val myConfigurator = context.getBean("supervision-with-dispatcher").asInstanceOf[TypedActorConfigurator]
      val foo = myConfigurator.getInstance(classOf[IFoo])
      assert(foo != null)
    }
  }
}