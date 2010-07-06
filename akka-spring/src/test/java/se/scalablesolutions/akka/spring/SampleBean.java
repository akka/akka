package se.scalablesolutions.akka.spring;

import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationContext;

import se.scalablesolutions.akka.actor.annotation.shutdown;

public class SampleBean implements ApplicationContextAware {

    public boolean down;

    public boolean gotApplicationContext;

    public SampleBean() {
        down = false;
        gotApplicationContext = false;
    }

    public void setApplicationContext(ApplicationContext context) {
  	    gotApplicationContext = true;
    }

    public String foo(String s) {
        return "hello " + s;
    }

    @shutdown
    public void shutdown() {
        down = true;
    }

 }
