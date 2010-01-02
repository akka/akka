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

  public void testInvokingAkkaEnabledSpringBean() {
    ApplicationContext context = new ClassPathXmlApplicationContext("spring-test-config.xml");
    MyService myService = (MyService) context.getBean("actorBeanService");
    Object obj = myService.getNumbers(12, "vfsh");
    assertEquals(new Integer(12), obj);
  }
}
