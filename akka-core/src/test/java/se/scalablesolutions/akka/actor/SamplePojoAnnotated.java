package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.annotation.postrestart;
import se.scalablesolutions.akka.actor.annotation.prerestart;
import se.scalablesolutions.akka.actor.annotation.shutdown;

import java.util.concurrent.CountDownLatch;

public class SamplePojoAnnotated {

    private CountDownLatch latch;

    public boolean _pre = false;
    public boolean _post = false;
    public boolean _down = false;

    public SamplePojoAnnotated() {
        latch = new CountDownLatch(1);
    }

    public CountDownLatch newCountdownLatch(int count) {
        latch = new CountDownLatch(count);
        return latch;
    }

    public String greet(String s) {
        return "hello " + s;
    }

    public String fail() {
        throw new RuntimeException("expected");
    }

    @prerestart
    public void pre() {
        _pre = true;
        latch.countDown();
    }

    @postrestart
    public void post() {
        _post = true;
        latch.countDown();
    }

    @shutdown
    public void down() {
        _down = true;
        latch.countDown();
    }

}