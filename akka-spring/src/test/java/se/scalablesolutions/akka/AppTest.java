package se.scalablesolutions.akka;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import se.scalablesolutions.akka.service.MyService;

/**
 * Unit test for simple App.
 */
public class AppTest 
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public AppTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( AppTest.class );
    }


    public void testApp()
    {

        ApplicationContext context = new ClassPathXmlApplicationContext("spring-test-config.xml");

	    MyService myService = (MyService)context.getBean("interceptedService");

	    System.out.println(Thread.currentThread());
		
		Object obj = myService.getNumbers(12,"vfsh");
		
	    assertEquals(new Integer(12), obj);

    }
}
