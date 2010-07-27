package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;
import se.scalablesolutions.akka.dispatch.CompletableFuture;

public class SimpleJavaPojoImpl extends TypedActor implements SimpleJavaPojo {

  public boolean pre = false;
  public boolean post = false;

  private String name;

  public boolean pre() { 
    return pre;
  }
  
  public boolean post() { 
    return post;
  }
  
  public Object getSender() {
    return getContext().getSender();
  }

  public CompletableFuture<Object> getSenderFuture() {
    return getContext().getSenderFuture();
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  @Override
  public void preRestart(Throwable e) {
    System.out.println("** pre()");
    pre = true;
  }

  @Override
  public void postRestart(Throwable e) {
    System.out.println("** post()");
    post = true;
  }

  public void throwException() {
    throw new RuntimeException();
  }
}
