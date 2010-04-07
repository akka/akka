/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import se.scalablesolutions.akka.actor.ActiveObject;
import se.scalablesolutions.akka.config.ActiveObjectConfigurator;
import se.scalablesolutions.akka.spring.foo.Foo;
import se.scalablesolutions.akka.spring.foo.IBar;
import se.scalablesolutions.akka.spring.foo.MyPojo;
import se.scalablesolutions.akka.spring.foo.StatefulPojo;


/**
 * Testclass for supervisor configuration.
 * @author michaelkober
 *
 */
public class SupervisorConfigurationTest {
        
        private ApplicationContext context = null;

          @Before
          public void setUp() {
            context = new ClassPathXmlApplicationContext("se/scalablesolutions/akka/spring/foo/supervisor-config.xml");
          }
        
         @Test
          public void testSupervision() {
            // get ActiveObjectConfigurator bean from spring context
            ActiveObjectConfigurator myConfigurator = (ActiveObjectConfigurator) context.getBean("supervision1");
            // get ActiveObjects
            Foo foo = myConfigurator.getInstance(Foo.class);
            assertNotNull(foo);
            IBar bar = myConfigurator.getInstance(IBar.class);
            assertNotNull(bar);
            MyPojo pojo = myConfigurator.getInstance(MyPojo.class);
            assertNotNull(pojo);
          }

          @Test
          public void testTransactionalState() {
            ActiveObjectConfigurator conf = (ActiveObjectConfigurator) context.getBean("supervision2");
            StatefulPojo stateful = conf.getInstance(StatefulPojo.class);
            stateful.setMapState("testTransactionalState", "some map state");
            stateful.setVectorState("some vector state");
            stateful.setRefState("some ref state");
            assertEquals("some map state", stateful.getMapState("testTransactionalState"));
            assertEquals("some vector state", stateful.getVectorState());
            assertEquals("some ref state", stateful.getRefState());
          }
          
        @Test
      	public void testInitTransactionalState() {
      		StatefulPojo stateful = ActiveObject.newInstance(StatefulPojo.class, 1000, true);
            assertTrue("should be inititalized", stateful.isInitialized());
      	}

      @Test
          public void testSupervisionWithDispatcher() {
            ActiveObjectConfigurator myConfigurator = (ActiveObjectConfigurator) context.getBean("supervision-with-dispatcher");
            // get ActiveObjects
            Foo foo = myConfigurator.getInstance(Foo.class);
            assertNotNull(foo);
            // TODO how to check dispatcher?
          }

}
