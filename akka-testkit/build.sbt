import akka.{ AkkaBuild, Formatting, OSGi, Unidoc, Dependencies }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.testkit

// to fix scaladoc generation
libraryDependencies ++= Dependencies.testkit

initialCommands += "import akka.testkit._"

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-testkit").value
