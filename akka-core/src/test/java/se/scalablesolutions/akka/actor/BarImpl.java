package se.scalablesolutions.akka.actor;

import com.google.inject.Inject;

public class BarImpl implements Bar {
  @Inject
  private Ext ext;
  public Ext getExt() {
    return ext;
  }
  public void bar(String msg) {
  }
}
