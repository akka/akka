import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.osgi

Dependencies.osgi

parallelExecution in Test := false

MimaKeys.reportBinaryIssues := () // disable bin comp check
