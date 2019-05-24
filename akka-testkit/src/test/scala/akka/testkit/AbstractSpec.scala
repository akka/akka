/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import org.scalatest.BeforeAndAfterEach
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

// we could migrate AkkaSpec to extend this
abstract class AbstractSpec extends WordSpecLike with Matchers with BeforeAndAfterEach
