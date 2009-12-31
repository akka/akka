package se.scalablesolutions.akka.main;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import se.scalablesolutions.akka.service.MyService;

public class Main {
    	public static void main(String[] args) {

		ApplicationContext context = new ClassPathXmlApplicationContext("AkkaAppConfig.xml");

	    MyService myService = (MyService)context.getBean("interceptedService");

	    System.out.println(Thread.currentThread());

	    myService.getNumbers(777,"vfsh");

	}
}
