package akka.remote.artery

object FlightRecorderEvents {

  // Note: Remember to update dictionary when adding new events!

  val NoMetaData = Array.empty[Byte]

  // Top level remoting events
  val Transport_MediaDriverStarted = 0
  val Transport_AeronStarted = 1
  val Transport_AeronErrorLogStarted = 2
  val Transport_TaskRunnerStarted = 3
  val Transport_UniqueAddressSet = 4
  val Transport_MaterializerStarted = 5
  val Transport_StartupFinished = 6
  val Transport_OnAvailableImage = 7
  val Transport_KillSwitchPulled = 8
  val Transport_Stopped = 9
  val Transport_AeronErrorLogTaskStopped = 10
  val Transport_MediaFileDeleted = 11
  val Transport_FlightRecorderClose = 12
  val Transport_SendQueueOverflow = 13

  // Aeron Sink events
  val AeronSink_Started = 50
  val AeronSink_TaskRunnerRemoved = 51
  val AeronSink_PublicationClosed = 52
  val AeronSink_Stopped = 53
  val AeronSink_EnvelopeGrabbed = 54
  val AeronSink_EnvelopeOffered = 55
  val AeronSink_GaveUpEnvelope = 56
  val AeronSink_DelegateToTaskRunner = 57
  val AeronSink_ReturnFromTaskRunner = 58

  // Aeron Source events
  val AeronSource_Started = 70
  val AeronSource_Stopped = 71
  val AeronSource_Received = 72
  val AeronSource_DelegateToTaskRunner = 73
  val AeronSource_ReturnFromTaskRunner = 74

  // Compression events
  val Compression_CompressedActorRef = 90
  val Compression_AllocatedActorRefCompressionId = 91
  val Compression_CompressedManifest = 91
  val Compression_AllocatedManifestCompressionId = 92

  // Used for presentation of the entries in the flight recorder
  lazy val eventDictionary = Map(
    Transport_MediaDriverStarted → "Transport: Media driver started",
    Transport_AeronStarted → "Transport: Aeron started",
    Transport_AeronErrorLogStarted → "Transport: Aeron error log started",
    Transport_TaskRunnerStarted → "Transport: Task runner started",
    Transport_UniqueAddressSet → "Transport: Unique address set",
    Transport_MaterializerStarted → "Transport: Materializer started",
    Transport_StartupFinished → "Transport: Startup finished",
    Transport_OnAvailableImage → "Transport: onAvailableImage",
    Transport_KillSwitchPulled → "Transport: KillSwitch pulled",
    Transport_Stopped → "Transport: Stopped",
    Transport_AeronErrorLogTaskStopped → "Transport: Aeron errorLog task stopped",
    Transport_MediaFileDeleted → "Transport: Media file deleted",
    Transport_FlightRecorderClose → "Transport: Flight recorder closed",
    Transport_SendQueueOverflow → "Transport: Send queue overflow",

    // Aeron Sink events
    AeronSink_Started → "AeronSink: Started",
    AeronSink_TaskRunnerRemoved → "AeronSink: Task runner removed",
    AeronSink_PublicationClosed → "AeronSink: Publication closed",
    AeronSink_Stopped → "AeronSink: Stopped",
    AeronSink_EnvelopeGrabbed → "AeronSink: Envelope grabbed",
    AeronSink_EnvelopeOffered → "AeronSink: Envelope offered",
    AeronSink_GaveUpEnvelope → "AeronSink: Gave up envelope",
    AeronSink_DelegateToTaskRunner → "AeronSink: Delegate to task runner",
    AeronSink_ReturnFromTaskRunner → "AeronSink: Return from task runner",

    // Aeron Source events
    AeronSource_Started → "AeronSource: Started",
    AeronSource_Stopped → "AeronSource: Stopped",
    AeronSource_Received → "AeronSource: Received",
    AeronSource_DelegateToTaskRunner → "AeronSource: Delegate to task runner",
    AeronSource_ReturnFromTaskRunner → "AeronSource: Return from task runner",

    // Compression events
    Compression_CompressedActorRef → "Compression: Compressed ActorRef",
    Compression_AllocatedActorRefCompressionId → "Compression: Allocated ActorRef compression id",
    Compression_CompressedManifest → "Compression: Compressed manifest",
    Compression_AllocatedManifestCompressionId → "Compression: Allocated manifest compression id").map { case (int, str) ⇒ int.toLong → str }
}
