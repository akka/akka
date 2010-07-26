/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import se.scalablesolutions.akka.config.Config;
import se.scalablesolutions.akka.dispatch.FutureTimeoutException;
import se.scalablesolutions.akka.remote.RemoteNode;
import se.scalablesolutions.akka.spring.foo.MyPojo;

/**
 * Tests for spring configuration of typed actors and supervisor configuration.
 * @author michaelkober
 */
public class TypedActorConfigurationTest {

  private ApplicationContext context = null;

  @Before
  public void setUp() {
    context = new ClassPathXmlApplicationContext("se/scalablesolutions/akka/spring/foo/test-config.xml");
  }

  /**
   * Tests that the &lt;akka:active-object/&gt; and &lt;akka:supervision/&gt; and &lt;akka:dispatcher/&gt; element
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
    assertTrue(beanFactory.containsBeanDefinition("dispatcher1"));
  }

  @Test
  public void testSimpleTypedActor() {
    MyPojo myPojo = (MyPojo) context.getBean("simple-active-object");
    String msg = myPojo.getFoo();
    msg += myPojo.getBar();
    assertEquals("wrong invocation order", "foobar", msg);
  }

  @Test(expected = FutureTimeoutException.class)
  public void testSimpleTypedActor_Timeout() {
    MyPojo myPojo = (MyPojo) context.getBean("simple-active-object");
    myPojo.longRunning();
  }

  @Test
  public void testSimpleTypedActor_NoTimeout() {
    MyPojo myPojo = (MyPojo) context.getBean("simple-active-object-long-timeout");
    String msg = myPojo.longRunning();
    assertEquals("this took long", msg);
  }

  @Test
  public void testTransactionalTypedActor() {
    MyPojo myPojo = (MyPojo) context.getBean("transactional-active-object");
    String msg = myPojo.getFoo();
    msg += myPojo.getBar();
    assertEquals("wrong invocation order", "foobar", msg);
  }

  @Test
  public void testRemoteTypedActor() {
    new Thread(new Runnable() {
      public void run() {
        RemoteNode.start();
      }
    }).start();
    try {
      Thread.currentThread().sleep(1000);
    } catch (Exception e) {
    }
    Config.config();

    MyPojo myPojo = (MyPojo) context.getBean("remote-active-object");
    assertEquals("foo", myPojo.getFoo());
  }


}
