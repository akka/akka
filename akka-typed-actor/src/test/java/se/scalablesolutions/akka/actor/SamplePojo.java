package akka.actor;

import java.util.concurrent.CountDownLatch;

public interface SamplePojo {
  public String greet(String s);
  public String fail();
}
