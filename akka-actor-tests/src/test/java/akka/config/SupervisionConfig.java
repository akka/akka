package akka.config;

import akka.actor.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static akka.config.Supervision.*;

public class SupervisionConfig {
 /*Just some sample code to demonstrate the declarative supervision configuration for Java */
 @SuppressWarnings("unchecked")
 public SupervisorConfig createSupervisorConfig(List<ActorRef> toSupervise) {
  ArrayList<Server> targets = new ArrayList<Server>(toSupervise.size());
   for(ActorRef ref : toSupervise) {
     targets.add(new Supervise(ref, permanent(), true));
   }


   return new SupervisorConfig(new AllForOnePermanentStrategy(new Class[] { Exception.class }, 50, 1000), targets.toArray(new Server[targets.size()]));
 }
}
