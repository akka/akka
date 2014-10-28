import akka.{AkkaBuild, Formatting, Unidoc, OSGi}
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-multi-node-testkit").value
