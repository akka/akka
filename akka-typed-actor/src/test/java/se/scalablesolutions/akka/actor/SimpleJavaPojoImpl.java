package akka.actor;

import akka.actor.*;
import akka.dispatch.Future;
import akka.dispatch.CompletableFuture;
import akka.japi.Option;

public class SimpleJavaPojoImpl extends TypedActor implements SimpleJavaPojo {

  public static boolean _pre = false;
  public static boolean _post = false;
  public static boolean _down = false;
  public static void reset() {
    _pre = false;
    _post = false;
    _down = false;
  }

  private String name;

  public Future<Integer> square(int value) {
    return future(value * value);
  }

  public Object getSender() {
    return getContext().getSender();
  }

  public CompletableFuture<Object> getSenderFuture() {
    return getContext().getSenderFuture().get();
  }

  public Option<String> passThru(Option<String> returnValue) {
      return returnValue;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  @Override
  public void preRestart(Throwable e) {
    _pre = true;
  }

  @Override
  public void postRestart(Throwable e) {
    _post = true;
  }

  public void throwException() {
    throw new RuntimeException();
  }
}
