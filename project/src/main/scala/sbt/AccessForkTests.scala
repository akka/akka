package sbt
package multijvmaccess

import sbt.ConcurrentRestrictions.Tag
import sbt.Tests.Execution
import sbt.testing.Runner

object Access {
  def ForkTests(
             runners: Map[TestFramework, Runner],
             tests: Vector[TestDefinition],
             config: Execution,
             classpath: Seq[File],
             fork: ForkOptions,
             log: Logger,
             tag: Tag
           ): Task[sbt.Tests.Output] = sbt.ForkTests(runners, tests, config, classpath, fork, log, tag)
}
