package akka.actor;

import akka.dispatch.Future;
import akka.dispatch.CompletableFuture;
import akka.dispatch.Future;
import akka.japi.Option;

public interface SimpleJavaPojo {
  public Object getSender();
  public Object getSenderFuture();
  public Future<Integer> square(int value);
  public Option<String> passThru(Option<String> returnValue);
  public void setName(String name);
  public String getName();
  public void throwException();
}
