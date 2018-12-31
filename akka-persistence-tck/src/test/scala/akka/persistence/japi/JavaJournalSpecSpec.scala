/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.japi

import akka.persistence.japi.journal.JavaJournalSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.DoNotDiscover

/* Only checking that compilation works with the constructor here as expected (no other abstract fields leaked) */
@DoNotDiscover
class JavaJournalSpecSpec extends JavaJournalSpec(ConfigFactory.parseString(""))
