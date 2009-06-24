package se.scalablesolutions.akka.api;

import java.io.Serializable;

public class InMemFailer { 
  public void fail() {
    throw new RuntimeException("expected");
  }
}
