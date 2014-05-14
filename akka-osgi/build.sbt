import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.osgi

libraryDependencies ++= Dependencies.osgi

parallelExecution in Test := false

MimaKeys.reportBinaryIssues := () // disable bin comp check
