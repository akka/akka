/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.japi.snapshot

import akka.persistence.CapabilityFlag
import akka.persistence.snapshot.SnapshotStoreSpec
import com.typesafe.config.Config
import org.scalatest.{ Args, ConfigMap, Filter, Status, Suite, TestData }

/**
 * JAVA API
 *
 * This spec aims to verify custom akka-persistence [[akka.persistence.snapshot.SnapshotStore]] implementations.
 * Plugin authors are highly encouraged to include it in their plugin's test suites.
 *
 * In case your snapshot-store plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overridden methods).
 *
 * @see [[akka.persistence.snapshot.SnapshotStoreSpec]]
 */
class JavaSnapshotStoreSpec(config: Config) extends SnapshotStoreSpec(config) {
  // FIXME workarounds for https://github.com/scala/bug/issues/11512
  override def rerunner: Option[String] = super.rerunner
  override def runTest(testName: String, args: Args): Status = super.runTest(testName, args)
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

  override protected def supportsSerialization: CapabilityFlag = CapabilityFlag.on
}
