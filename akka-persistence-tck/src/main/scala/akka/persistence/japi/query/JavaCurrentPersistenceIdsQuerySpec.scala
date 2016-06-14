package akka.persistence.japi.query

import akka.persistence.CapabilityFlag
import akka.persistence.query.{ CurrentPersistenceIdsQuerySpec, QuerySpec }
import com.typesafe.config.Config

///**
// * JAVA API
// *
// * Java / JUnit API for [[akka.persistence.query.CurrentPersistenceIdsQuerySpec]].
// *
// * In case your journal plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
// * methods (don't forget to call `super` in your overridden methods).
// *
// * @see [[akka.persistence.query.CurrentPersistenceIdsQuerySpec]]
// * @param config configures the Query plugin to be tested
// */
////abstract class JavaCurrentPersistenceIdsQuerySpec(config: Config) extends QuerySpec(config) with CurrentPersistenceIdsQuerySpec {
////  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.on
////}

