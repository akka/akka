package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;

import java.util.concurrent.CountDownLatch;

public class SamplePojoImpl extends TypedActor implements SamplePojo {

    private CountDownLatch latch;

    public boolean _pre = false;
    public boolean _post = false;
    public boolean _down = false;

    public SamplePojoImpl() {
        latch = new CountDownLatch(1);
    }

    public CountDownLatch newCountdownLatch(int count) {
        latch = new CountDownLatch(count);
        return latch;
    }

    public boolean pre() { 
      return _pre;
    }
    
    public boolean post() { 
      return _post;
    }
    
    public boolean down() { 
      return _down;
    }
    
    public String greet(String s) {
        return "hello " + s;
    }

    public String fail() {
        throw new RuntimeException("expected");
    }

    @Override
    public void preRestart(Throwable e) {
      _pre = true;
      latch.countDown();
    }

    @Override
    public void postRestart(Throwable e) {
      _post = true;
      latch.countDown();
    }
    
    @Override
    public void shutdown() {
        _down = true;
        latch.countDown();
    }
}