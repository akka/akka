package akka.actor;

import akka.dispatch.CompletableFuture;

public interface SimpleJavaPojoCaller {
  public void setPojo(SimpleJavaPojo pojo);
  public Object getSenderFromSimpleJavaPojo();
  public Object getSenderFutureFromSimpleJavaPojo();
}
