package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.dispatch.CompletableFuture;

public interface SimpleJavaPojoCaller {
  public void setPojo(SimpleJavaPojo pojo);
  public Object getSenderFromSimpleJavaPojo();
  public Object getSenderFutureFromSimpleJavaPojo();
}
