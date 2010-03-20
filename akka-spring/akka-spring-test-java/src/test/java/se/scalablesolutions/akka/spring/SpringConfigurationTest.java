package se.scalablesolutions.akka.spring;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import se.scalablesolutions.akka.config.ActiveObjectConfigurator;
import se.scalablesolutions.akka.dispatch.FutureTimeoutException;
import se.scalablesolutions.akka.config.Config;
import se.scalablesolutions.akka.remote.RemoteNode;
import se.scalablesolutions.akka.spring.foo.Foo;
import se.scalablesolutions.akka.spring.foo.IBar;
import se.scalablesolutions.akka.spring.foo.MyPojo;
import se.scalablesolutions.akka.spring.foo.StatefulPojo;

/**
 * Tests for spring configuration of active objects and supervisor configuration.
 */
public class SpringConfigurationTest {

    private ApplicationContext context = null;

    @Before
    public void setUp() {
        context = new ClassPathXmlApplicationContext("se/scalablesolutions/akka/spring/foo/test-config.xml");
    }

   /**
    * Tests that the &lt;akka:active-object/&gt; and &lt;akka:supervision/&gt; element
    * can be used as a top level element.
    */
    @Test
        public void testParse() throws Exception {
        final Resource CONTEXT = new ClassPathResource("se/scalablesolutions/akka/spring/foo/test-config.xml");
                DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
                XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(beanFactory);
                reader.loadBeanDefinitions(CONTEXT);
                assertTrue(beanFactory.containsBeanDefinition("simple-active-object"));
                assertTrue(beanFactory.containsBeanDefinition("remote-active-object"));
                assertTrue(beanFactory.containsBeanDefinition("supervision1"));
        }
        
        @Test
        public void testSimpleActiveObject() {
                MyPojo myPojo = (MyPojo) context.getBean("simple-active-object");
        String msg = myPojo.getFoo();
        msg += myPojo.getBar();
        assertEquals("wrong invocation order", "foobar", msg);
        }

    @Test(expected=FutureTimeoutException.class)
        public void testSimpleActiveObject_Timeout() {
                MyPojo myPojo = (MyPojo) context.getBean("simple-active-object");
        myPojo.longRunning();
        }

   @Test
        public void testSimpleActiveObject_NoTimeout() {
                MyPojo myPojo = (MyPojo) context.getBean("simple-active-object-long-timeout");
        String msg = myPojo.longRunning();
        assertEquals("this took long", msg);
        }
  
        @Test
        public void testTransactionalActiveObject() {
                MyPojo myPojo = (MyPojo) context.getBean("transactional-active-object");
        String msg = myPojo.getFoo();
        msg += myPojo.getBar();
        assertEquals("wrong invocation order", "foobar", msg);
        }
        
        @Test
        public void testRemoteActiveObject() {
      new Thread(new Runnable() {
         public void run() {
         RemoteNode.start();
        }
      }).start();
      try { Thread.currentThread().sleep(1000);  } catch (Exception e) {}
      Config.config();

          MyPojo myPojo = (MyPojo) context.getBean("remote-active-object");
      assertEquals("foo", myPojo.getFoo());
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
        stateful.init();
        stateful.setMapState("testTransactionalState", "some map state");
        stateful.setVectorState("some vector state");
        stateful.setRefState("some ref state");
        assertEquals("some map state", stateful.getMapState("testTransactionalState"));
        assertEquals("some vector state", stateful.getVectorState());
        assertEquals("some ref state", stateful.getRefState());
    }
        
}
