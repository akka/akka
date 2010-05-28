/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;

import se.scalablesolutions.akka.actor.ActiveObject;
import se.scalablesolutions.akka.actor.ActiveObjectContext;

public class Receiver {
  private ActiveObjectContext context = null;
  public SimpleService receive() {
    System.out.println("------ RECEIVE");
    return (SimpleService) context.getSender();
  }
}
