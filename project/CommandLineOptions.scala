package akka

object CommandLineOptions {

  /**
   * Aggregated sample builds are transformed by swapping library dependencies to project ones.
   * This does work play well with dbuild and breaks scala community build. Therefore it was made
   * optional.
   *
   * Default: true
   */
  val aggregateSamples  = sys.props.getOrElse("akka.build.aggregateSamples", "true") == "true"
}
