/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import foo.{ IMyPojo, MyPojo, PingActor }
import akka.dispatch._
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
import akka.actor.{ UntypedActor, Actor, ActorRef }

/**
 * Tests for spring configuration of typed actors.
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class ConfiggyPropertyPlaceholderConfigurerSpec extends FeatureSpec with ShouldMatchers {
  val EVENT_DRIVEN_PREFIX = "akka:event-driven:dispatcher:"

  feature("The ConfiggyPropertyPlaceholderConfigurator") {

    scenario("should provide the akkka config for spring") {
      val context = new ClassPathXmlApplicationContext("/property-config.xml")
      val actor1 = context.getBean("actor-1").asInstanceOf[ActorRef]
      assert(actor1.homeAddress.get.getHostName === "localhost")
      assert(actor1.homeAddress.get.getPort === 9995)
      assert(actor1.timeout === 2000)
    }
  }
}
