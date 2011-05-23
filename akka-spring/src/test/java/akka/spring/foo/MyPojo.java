package akka.spring.foo;

import akka.actor.TypedActor;

import java.util.concurrent.CountDownLatch;

public class MyPojo extends TypedActor implements IMyPojo {

  public static CountDownLatch latch = new CountDownLatch(1);
  public static String lastOneWayMessage = null;
  private String foo = "foo";


  public MyPojo() {
  }

  public String getFoo() {
    return foo;
  }

  public void oneWay(String message) {
    lastOneWayMessage = message;
    latch.countDown();
  }

  public String longRunning() {
    try {
      Thread.sleep(6000);
    } catch (InterruptedException e) {
    }
    return "this took long";
  }

}
