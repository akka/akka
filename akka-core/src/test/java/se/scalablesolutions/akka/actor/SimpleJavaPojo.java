package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.dispatch.CompletableFuture;

public interface SimpleJavaPojo {
  public Object getSender();
  public CompletableFuture<Object> getSenderFuture();
  public void setName(String name);
  public String getName();
  public void throwException();
}
