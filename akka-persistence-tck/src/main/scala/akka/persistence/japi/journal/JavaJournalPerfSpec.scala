/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.japi.journal

import akka.persistence.journal.{ JournalPerfSpec, JournalSpec }
import com.typesafe.config.Config
import org.junit.runner.RunWith
import org.scalatest.Informer
import org.scalatest.junit.JUnitRunner

/**
 * JAVA API
 *
 * Java / JUnit consumable equivalent of [[akka.persistence.journal.JournalPerfSpec]] and [[akka.persistence.journal.JournalSpec]].
 *
 * This spec measures execution times of the basic operations that an [[akka.persistence.PersistentActor]] provides,
 * using the provided Journal (plugin).
 *
 * It is *NOT* meant to be a comprehensive benchmark, but rather aims to help plugin developers to easily determine
 * if their plugin's performance is roughly as expected. It also validates the plugin still works under "more messages" scenarios.
 *
 * The measurements are by default printed to `System.out`, if you want to customise this please override the [[#info]] method.
 *
 * The benchmark iteration and message counts are easily customisable by overriding these methods:
 *
 * {{{
 *   @Override
 *   public long awaitDurationMillis() { return 10000; }
 *
 *   @Override
 *   public int eventsCount() { return 10 * 1000; }
 *
 *   @Override
 *   public int measurementIterations { return 10; }
 * }}}
 *
 * In case your journal plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overriden methods).
 *
 * @see [[akka.persistence.journal.JournalSpec]]
 * @see [[akka.persistence.journal.JournalPerfSpec]]
 * @param config configures the Journal plugin to be tested
 */
@RunWith(classOf[JUnitRunner])
class JavaJournalPerfSpec(val config: Config) extends JournalSpec with JournalPerfSpec {
  override protected def info: Informer = new Informer {
    override def apply(message: String, payload: Option[Any]): Unit = System.out.println(message)
  }
}
