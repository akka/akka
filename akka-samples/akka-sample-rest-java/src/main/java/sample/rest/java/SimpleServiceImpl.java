/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import akka.actor.TypedActor;
import akka.stm.local.Atomic;
import akka.stm.TransactionalMap;

public class SimpleServiceImpl extends TypedActor implements SimpleService {
  private String KEY = "COUNTER";

  private boolean hasStartedTicking = false;
  private final TransactionalMap<String, Integer> storage = new TransactionalMap<String, Integer>();
  private Receiver receiver = TypedActor.newInstance(Receiver.class, ReceiverImpl.class);

  public String count() {
    if (!hasStartedTicking) {
      new Atomic() {
        public Object atomically() {
          storage.put(KEY, 0);
          return null;
        }
      }.execute();
      hasStartedTicking = true;
      return "Tick: 0\n";
    } else {
      // Grabs the sender address and returns it
      //SimpleService sender = receiver.receive();
      int counter = new Atomic<Integer>() {
        public Integer atomically() {
          int count = (Integer) storage.get(KEY).get() + 1;
          storage.put(KEY, count);
          return count;
        }
      }.execute();
      return "Tick: " + counter + "\n";
    }
  }

  @Override
  public void preRestart(Throwable cause) {
    System.out.println("Prepare for restart by supervisor");
  }

  @Override
  public void postRestart(Throwable cause) {
    System.out.println("Reinitialize after restart by supervisor");
  }
}
