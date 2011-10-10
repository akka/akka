/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.AkkaApplication

abstract class AkkaSpec(_application: AkkaApplication = AkkaApplication())
  extends TestKit(_application) with WordSpec with MustMatchers {

}