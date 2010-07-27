package se.scalablesolutions.akka.actor;

import java.util.concurrent.CountDownLatch;

public interface SamplePojo {
  public boolean pre();
  public boolean post();
  public boolean down();
  public CountDownLatch newCountdownLatch(int count);
  public String greet(String s);
  public String fail();
}