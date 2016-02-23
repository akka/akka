/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.jrouting;

//#group
import java.util.List;

import scala.Option;
import scala.collection.immutable.Iterable;
import akka.actor.ActorContext;
import akka.actor.ActorPath;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.Dispatchers;
import akka.routing.Group;
import akka.routing.Routee;
import akka.routing.Router;
import akka.routing.RouterActor;
import akka.routing.RouterConfig;
import akka.routing.RoutingLogic;

import com.typesafe.config.Config;

import akka.routing.GroupBase;
import static docs.jrouting.CustomRouterDocTest.RedundancyRoutingLogic;

public class RedundancyGroup extends GroupBase {
  private final List<String> paths;
  private final int nbrCopies;
  
  public RedundancyGroup(List<String> paths, int nbrCopies) {
    this.paths = paths;
    this.nbrCopies = nbrCopies;
  }

  public RedundancyGroup(Config config) {
    this(config.getStringList("routees.paths"),
      config.getInt("nbr-copies"));
  }
  
  @Override
  public java.lang.Iterable<String> getPaths(ActorSystem system) {
    return paths;
  }

  @Override
  public Router createRouter(ActorSystem system) {
    return new Router(new RedundancyRoutingLogic(nbrCopies));
  }

  @Override 
  public String routerDispatcher() {
    return Dispatchers.DefaultDispatcherId();
  }
 
}
//#group