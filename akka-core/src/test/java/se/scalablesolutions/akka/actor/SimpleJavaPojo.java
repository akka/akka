package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.dispatch.Future;
import se.scalablesolutions.akka.dispatch.CompletableFuture;
import se.scalablesolutions.akka.dispatch.Future;

public interface SimpleJavaPojo {
  public Object getSender();
  public CompletableFuture<Object> getSenderFuture();
  public Future<Integer> square(int value);
  public void setName(String name);
  public String getName();
  public void throwException();
}
