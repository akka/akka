/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.japi.journal

import akka.persistence.journal.JournalSpec
import com.typesafe.config.Config
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
 * JAVA API
 *
 * Java / JUnit API for [[akka.persistence.journal.JournalSpec]].
 *
 * In case your journal plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overriden methods).
 *
 * @see [[akka.persistence.journal.JournalSpec]]
 * @see [[akka.persistence.journal.JournalPerfSpec]]
 * @param config configures the Journal plugin to be tested
 */
@RunWith(classOf[JUnitRunner])
class JavaJournalSpec(val config: Config) extends JournalSpec
