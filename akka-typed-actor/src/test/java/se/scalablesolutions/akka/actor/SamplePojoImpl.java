package akka.actor;

import akka.actor.*;

import java.util.concurrent.CountDownLatch;

public class SamplePojoImpl extends TypedActor implements SamplePojo {

    public static CountDownLatch latch = new CountDownLatch(1);

    public static boolean _pre = false;
    public static boolean _post = false;
    public static boolean _down = false;
    public static void reset() {
      _pre = false;
      _post = false;
      _down = false;
    }

    public String greet(String s) {
        return "hello " + s;
    }

    public String fail() {
        throw new RuntimeException("expected");
    }

    public akka.dispatch.Future<String> someFutureString() {
        return future("foo");
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
    public void postStop() {
        _down = true;
        latch.countDown();
    }
}
