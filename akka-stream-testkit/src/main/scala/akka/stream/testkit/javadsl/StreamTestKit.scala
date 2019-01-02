/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.javadsl

import akka.stream.Materializer
import akka.stream.impl.PhasedFusingActorMaterializer
import akka.stream.testkit.scaladsl

object StreamTestKit {

  /**
   * Assert that there are no stages running under a given materializer.
   * Usually this assertion is run after a test-case to check that all of the
   * stages have terminated successfully.
   */
  def assertAllStagesStopped(mat: Materializer): Unit =
    mat match {
      case impl: PhasedFusingActorMaterializer ⇒
        scaladsl.StreamTestKit.assertNoChildren(impl.system, impl.supervisor)
      case _ ⇒
    }
}
