package se.scalablesolutions.akka.actor;

import com.google.inject.Inject;
import se.scalablesolutions.akka.actor.*;

public class BarImpl extends TypedActor implements Bar {
  @Inject
  private Ext ext;

  public Ext getExt() {
    return ext;
  }

  public void bar(String msg) {
  }
}
