package se.scalablesolutions.akka.api;

public class PersistentFailer  implements Serializable {
  public int fail() {
    throw new RuntimeException("expected");
  }
}
