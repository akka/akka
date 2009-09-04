package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.oneway;

public interface Bar {
  @oneway
  void bar(String msg);
  Ext getExt();
}
