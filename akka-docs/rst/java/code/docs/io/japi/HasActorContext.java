/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

import akka.actor.ActorContext;
import akka.io.PipelineContext;

//#actor-context
public interface HasActorContext extends PipelineContext {
  
  public ActorContext getContext();

}
//#actor-context
