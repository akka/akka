package se.scalablesolutions.akka.config;

import se.scalablesolutions.akka.actor.ActorRef;

import java.util.ArrayList;
import java.util.List;

import static se.scalablesolutions.akka.config.Supervision.*;

public class SupervisionConfig {
  /*Just some sample code to demonstrate the declarative supervision configuration for Java */
 public SupervisorConfig createSupervisorConfig(List<ActorRef> toSupervise) {
  ArrayList<Server> targets = new ArrayList<Server>(toSupervise.size());
   for(ActorRef ref : toSupervise) {
     targets.add(new Supervise(ref, permanent(), new RemoteAddress("localhost",9999)));
   }

   return new SupervisorConfig(new RestartStrategy(new OneForOne(),50,1000,new Class[]{ Exception.class}), targets.toArray(new Server[0]));
 }
}
