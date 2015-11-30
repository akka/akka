import akka.{ AkkaBuild, Formatting, OSGi, Dependencies }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.testkit

// to fix scaladoc generation
Dependencies.testkit

initialCommands += "import akka.testkit._"

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-testkit").value
