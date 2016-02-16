package akka.http.impl.engine.parsing

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ParserSettings
import com.typesafe.config.{ ConfigFactory, Config }

object HttpHeaderParserTestBed extends App {

  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.http.parsing.max-header-name-length = 20
    akka.http.parsing.max-header-value-length = 21
    akka.http.parsing.header-cache.Host = 300""")
  val system = ActorSystem("HttpHeaderParserTestBed", testConf)

  val parser = HttpHeaderParser.prime {
    HttpHeaderParser.unprimed(ParserSettings(system), warnOnIllegalHeader = info ⇒ system.log.warning(info.formatPretty))
  }

  println {
    s"""
       |HttpHeaderParser primed Trie
       |----------------------------
       |
       |%TRIE%
       |
       |formatSizes: ${parser.formatSizes}
       |contentHistogram: ${parser.contentHistogram.mkString("\n  ", "\n  ", "\n")}
     """.stripMargin.replace("%TRIE%", parser.formatTrie)
  }

  system.terminate()
}
