package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.dispatch.CompletableFuture;

public class SimpleJavaPojoCaller {

  SimpleJavaPojo pojo;
  
  public void setPojo(SimpleJavaPojo pojo) {
    this.pojo = pojo;
  }

  public Object getSenderFromSimpleJavaPojo() {
    return pojo.getSender();
  }

  public CompletableFuture<Object> getSenderFutureFromSimpleJavaPojo() {
    return pojo.getSenderFuture();
  }
}
