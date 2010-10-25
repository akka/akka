package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.dispatch.Future;
import se.scalablesolutions.akka.dispatch.CompletableFuture;
import se.scalablesolutions.akka.dispatch.Future;
import se.scalablesolutions.akka.japi.Option;

public interface SimpleJavaPojo {
  public Object getSender();
  public Object getSenderFuture();
  public Future<Integer> square(int value);
  public Option<String> middleName();
  public void setName(String name);
  public String getName();
  public void throwException();
}
