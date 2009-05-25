package se.scalablesolutions.akka.api;

import com.google.inject.Inject;
import se.scalablesolutions.akka.annotation.oneway;

public class Foo {
  @Inject
  private Bar bar;
  public Bar getBar() {
    return bar;
  }
  public String foo(String msg) {
    return msg + "return_foo ";
  }
  @oneway
  public void bar(String msg) {
    bar.bar(msg);
  }
  public void longRunning() {
    try {
      Thread.sleep(10000);
    } catch (InterruptedException e) {
    }
  }
  public void throwsException() {
    throw new RuntimeException("expected");
  }
}
