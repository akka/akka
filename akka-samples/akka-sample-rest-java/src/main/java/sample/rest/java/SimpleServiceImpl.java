/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import akka.actor.TypedActor;
import akka.actor.TypedTransactor;
import akka.stm.TransactionalMap;

public class SimpleServiceImpl extends TypedTransactor implements SimpleService {
  private String KEY = "COUNTER";

  private boolean hasStartedTicking = false;
  private TransactionalMap<String, Integer> storage;
  private Receiver receiver = TypedActor.newInstance(Receiver.class, ReceiverImpl.class);

  public String count() {
    if (storage == null) storage = new TransactionalMap<String, Integer>();
    if (!hasStartedTicking) {
      storage.put(KEY, 0);
      hasStartedTicking = true;
      return "Tick: 0\n";
    } else {
      // Grabs the sender address and returns it
      //SimpleService sender = receiver.receive();
      int counter = (Integer)storage.get(KEY).get() + 1;
      storage.put(KEY, counter);
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
