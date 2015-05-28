import akka.{AkkaBuild, Formatting, OSGi}
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-multi-node-testkit").value
