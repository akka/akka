package akka.actor;

import akka.actor.*;
import akka.dispatch.Future;

public class SimpleJavaPojoCallerImpl extends TypedActor implements SimpleJavaPojoCaller {

  SimpleJavaPojo pojo;

  public void setPojo(SimpleJavaPojo pojo) {
    this.pojo = pojo;
  }

  public Object getSenderFromSimpleJavaPojo() {
    Object sender = pojo.getSender();
    return sender;
  }

  public Object getSenderFutureFromSimpleJavaPojo() {
    return pojo.getSenderFuture();
  }

  public Future<Integer> square(int value) {
    return future(value * value);
  }
}
