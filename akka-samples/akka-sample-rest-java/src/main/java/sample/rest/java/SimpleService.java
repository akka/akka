/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import se.scalablesolutions.akka.actor.ActiveObject;
import se.scalablesolutions.akka.actor.ActiveObjectContext;
import se.scalablesolutions.akka.actor.annotation.transactionrequired;
import se.scalablesolutions.akka.actor.annotation.prerestart;
import se.scalablesolutions.akka.actor.annotation.postrestart;
import se.scalablesolutions.akka.stm.TransactionalState;
import se.scalablesolutions.akka.stm.TransactionalMap;

@transactionrequired
public class SimpleService {
  private String KEY = "COUNTER";

  private boolean hasStartedTicking = false;
  private TransactionalMap<String, Integer> storage;
  private Receiver receiver = ActiveObject.newInstance(Receiver.class);
  
  public String count() {
    if (storage == null) storage = TransactionalState.newMap();
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

  @prerestart
  public void preRestart() {
    System.out.println("Prepare for restart by supervisor");
  }

  @postrestart
  public void postRestart() {
    System.out.println("Reinitialize after restart by supervisor");
  }
}