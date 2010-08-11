/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring


import se.scalablesolutions.akka.spring.foo.{IMyPojo, MyPojo, IFoo, IBar}
import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.TypedActorConfigurator

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

    scenario("get a supervisor from context") {
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

    scenario("get a supervisor and dispatcher from context") {
      val context = new ClassPathXmlApplicationContext("/supervisor-config.xml")
      val myConfigurator = context.getBean("supervision-with-dispatcher").asInstanceOf[TypedActorConfigurator]
      val foo = myConfigurator.getInstance(classOf[IFoo])
      assert(foo != null)
    }
  }

  /*
@Test
        public void testTransactionalState() {
                TypedActorConfigurator conf = (TypedActorConfigurator) context
                                .getBean("supervision2");
                StatefulPojo stateful = conf.getInstance(StatefulPojo.class);
                stateful.setMapState("testTransactionalState", "some map state");
                stateful.setVectorState("some vector state");
                stateful.setRefState("some ref state");
                assertEquals("some map state", stateful
                                .getMapState("testTransactionalState"));
                assertEquals("some vector state", stateful.getVectorState());
                assertEquals("some ref state", stateful.getRefState());
        }

        @Test
        public void testInitTransactionalState() {
                StatefulPojo stateful = TypedActor.newInstance(StatefulPojo.class,
                                1000, true);
                assertTrue("should be inititalized", stateful.isInitialized());
        }


 @Test
        public void testSupervisedRemoteTypedActor() {
                new Thread(new Runnable() {
                        public void run() {
                                RemoteNode.start();
                        }
                }).start();
                try {
                        Thread.currentThread().sleep(1000);
                } catch (Exception e) {
                }

                TypedActorConfigurator conf = new TypedActorConfigurator();
                conf.configure(
                                new RestartStrategy(new AllForOne(), 3, 10000, new Class[] { Exception.class }),
                                new Component[] {
                                        new Component(
                                                        Foo.class,
                                                        new LifeCycle(new Permanent()),
                                                        10000,
                                                        new RemoteAddress("localhost", 9999))
                                        }).supervise();

                Foo instance = conf.getInstance(Foo.class);
                assertEquals("foo", instance.foo());
        }
        

   */

  
}