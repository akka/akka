import akka._
import com.typesafe.tools.mima.plugin.MimaKeys
import spray.boilerplate.BoilerplatePlugin._

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.http

Dependencies.http

MimaKeys.previousArtifacts := akkaStreamAndHttpPreviousArtifacts("akka-http").value

Boilerplate.settings
