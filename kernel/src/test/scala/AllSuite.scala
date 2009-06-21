/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import org.scalatest._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class AllSuite extends SuperSuite(

  List(
    new SupervisorSpec
 //    new ActiveObjectSpec,
//    new RestManagerSpec
  )
)


