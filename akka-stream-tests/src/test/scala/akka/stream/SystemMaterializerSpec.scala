/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.StreamSpec
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future

class SystemMaterializerEagerStartupSpec extends StreamSpec {

  "The SystemMaterializer" must {

    "be eagerly started on system startup" in {
      system.hasExtension(SystemMaterializer.lookup) should ===(true)
    }
  }

}
