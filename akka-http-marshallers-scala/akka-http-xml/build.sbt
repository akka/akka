import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.httpXml

Dependencies.httpXml

MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http-xml").value
