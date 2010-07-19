package se.scalablesolutions.akka.actor;

import java.util.concurrent.CountDownLatch;

public class SamplePojo {

    private CountDownLatch latch;

    public boolean _pre = false;
    public boolean _post = false;
    public boolean _down = false;

    public CountDownLatch newCountdownLatch(int count) {
        latch = new CountDownLatch(count);
        return latch;
    }

    public String fail() {
        throw new RuntimeException("expected");
    }

    public void pre() {
        _pre = true;
        latch.countDown();
    }

    public void post() {
        _post = true;
        latch.countDown();
    }

    public void down() {
        _down = true;
        latch.countDown();
    }

}
