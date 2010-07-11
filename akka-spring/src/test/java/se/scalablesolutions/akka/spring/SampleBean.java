package se.scalablesolutions.akka.spring;

import se.scalablesolutions.akka.actor.annotation.shutdown;

public class SampleBean {

    public boolean down;

    public SampleBean() {
        down = false;
    }

    public String foo(String s) {
        return "hello " + s;
    }

    @shutdown
    public void shutdown() {
        down = true;
    }

 }
