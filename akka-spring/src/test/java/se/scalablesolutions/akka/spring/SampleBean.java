package se.scalablesolutions.akka.spring;

import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationContext;

public class SampleBean implements ApplicationContextAware {

	public boolean gotApplicationContext = false;
	
	public void setApplicationContext(ApplicationContext context) {
		gotApplicationContext = true;
	}

    public String foo(String s) {
        return "hello " + s;
    }
    
 }
