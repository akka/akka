package se.scalablesolutions.akka.spring;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class AkkaSpringInterceptorTest extends TestCase {

  public AkkaSpringInterceptorTest(String testName) {
    super(testName);
  }

  public static Test suite() {
    return new TestSuite(AkkaSpringInterceptorTest.class);
  }

  public void testInvokingAkkaEnabledSpringBeanMethodWithReturnValue() {
    ApplicationContext context = new ClassPathXmlApplicationContext("spring-test-config.xml");
    MyService myService = (MyService) context.getBean("actorBeanService");
    Object obj = myService.getNumbers(12);
    assertEquals(new Integer(12), obj);
  }

  public void testThatAkkaEnabledSpringBeanMethodCallIsInvokedInADifferentThreadThanTheTest() {
    ApplicationContext context = new ClassPathXmlApplicationContext("spring-test-config.xml");
    MyService myService = (MyService) context.getBean("actorBeanService");
    assertNotSame(Thread.currentThread().getName(), myService.getThreadName());
  }

  public void testInvokingAkkaEnabledSpringBeanMethodWithTransactionalMethod() {
    ApplicationContext context = new ClassPathXmlApplicationContext("spring-test-config.xml");
    MyService myService = (MyService) context.getBean("actorBeanService");
    myService.init();
    myService.setMapState("key", "value");
    myService.setRefState("value");
    myService.setVectorState("value");
    assertEquals("value", myService.getMapState("key"));
    assertEquals("value", myService.getRefState());
    assertEquals("value", myService.getVectorState());
  }
}
