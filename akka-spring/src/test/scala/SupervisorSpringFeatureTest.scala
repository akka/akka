/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.spring

import akka.spring.foo.{ IMyPojo, MyPojo, IFoo, IBar }
import akka.dispatch._
import akka.config.TypedActorConfigurator
import akka.actor.Supervisor

import org.scalatest.FeatureSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.ApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.core.io.{ ClassPathResource, Resource }
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
      assert(foo ne null)
      val bar = myConfigurator.getInstance(classOf[IBar])
      assert(bar ne null)
      val pojo = myConfigurator.getInstance(classOf[IMyPojo])
      assert(pojo ne null)
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
      assert(foo ne null)
    }
  }
}
