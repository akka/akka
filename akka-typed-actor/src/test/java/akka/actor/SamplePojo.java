package akka.actor;

import akka.dispatch.Future;

public interface SamplePojo {
  public String greet(String s);
  public String fail();
  public Future<String> someFutureString();
}
