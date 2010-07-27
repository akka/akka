package se.scalablesolutions.akka.actor;

import java.util.concurrent.CountDownLatch;

public interface SamplePojo {
  public CountDownLatch newCountdownLatch(int count);
  public String greet(String s);
  public String fail();
}