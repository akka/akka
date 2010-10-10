/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.riak

import se.scalablesolutions.akka.actor.{newUuid}
import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._

class RiakStorageBackend