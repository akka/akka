import akka.{AkkaBuild, Formatting, OSGi}
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-multi-node-testkit").value
