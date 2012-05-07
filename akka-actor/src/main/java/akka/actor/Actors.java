/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

/**
 * JAVA API for - creating actors, - creating remote actors, - locating actors
 */
@Deprecated("Will be removed in 2.1, all methods have been superceded.", "2.0.2")
public class Actors {
  /**
   * The message that is sent when an Actor gets a receive timeout.
   * 
   * <pre>
   * if (message == receiveTimeout()) {
   *   // Timed out
   * }
   * </pre>
   * 
   * @return the single instance of ReceiveTimeout
   */
  @Deprecated("Will be replaced by ReceiveTimeout.getInstance() in 2.1", "2.0.2")
  public final static ReceiveTimeout$ receiveTimeout() {
    return ReceiveTimeout$.MODULE$;
  }

  /**
   * The message that when sent to an Actor kills it by throwing an exception.
   * 
   * <pre>
   * actor.tell(kill());
   * </pre>
   * 
   * @return the single instance of Kill
   */
  @Deprecated("Will be replaced by Kill.getInstance() in 2.1", "2.0.2")
  public final static Kill$ kill() {
    return Kill$.MODULE$;
  }

  /**
   * The message that when sent to an Actor shuts it down by calling 'stop'.
   * 
   * <pre>
   * actor.tell(poisonPill());
   * </pre>
   * 
   * @return the single instance of PoisonPill
   */
  @Deprecated("Will be replaced by PoisonPill.getInstance() in 2.1", "2.0.2")
  public final static PoisonPill$ poisonPill() {
    return PoisonPill$.MODULE$;
  }
}
