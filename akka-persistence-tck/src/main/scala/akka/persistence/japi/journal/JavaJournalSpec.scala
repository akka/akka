/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.japi.journal

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import com.typesafe.config.Config
import org.scalatest.{ Args, ConfigMap, Filter, Status, Suite, TestData }

/**
 * JAVA API
 *
 * Java / JUnit API for [[akka.persistence.journal.JournalSpec]].
 *
 * In case your journal plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overridden methods).
 *
 * @see [[akka.persistence.journal.JournalSpec]]
 * @see [[akka.persistence.journal.JournalPerfSpec]]
 * @param config configures the Journal plugin to be tested
 */
class JavaJournalSpec(config: Config) extends JournalSpec(config) {

  // FIXME workarounds for https://github.com/scala/bug/issues/11512
  override def rerunner: Option[String] = super.rerunner
  override def runTest(testName: String, args: Args) = super.runTest(testName, args)
  override def run(testName: Option[String], args: Args): Status = super.run(testName, args)
  override def testDataFor(testName: String, theConfigMap: ConfigMap): TestData =
    super.testDataFor(testName, theConfigMap)
  override def testNames: Set[String] = super.testNames
  override def runTests(testName: Option[String], args: Args): Status = super.runTests(testName, args)
  override def tags: Map[String, Set[String]] = super.tags
  override def expectedTestCount(filter: Filter): Int = super.expectedTestCount(filter)
  override def suiteId: String = super.suiteId
  override def suiteName: String = super.suiteName
  override def runNestedSuites(args: Args): Status = super.runNestedSuites(args)
  override def nestedSuites = super.nestedSuites

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.on

  override protected def supportsSerialization: CapabilityFlag = CapabilityFlag.on
}
