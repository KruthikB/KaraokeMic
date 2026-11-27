import AVFoundation
import AudioToolbox
import Foundation

enum KaraokeEngineError: LocalizedError {
  case permissionDenied
  case noInput
  case engineFailed(String)

  var errorDescription: String? {
    switch self {
    case .permissionDenied:
      return "Microphone permission is off. Enable the microphone for StageMic in Settings."
    case .noInput:
      return "No microphone input is available. Use a real iPhone rather than the Simulator."
    case .engineFailed(let message):
      return "The mic did not start. \(message)"
    }
  }
}

/// Live vocal monitor. The session is playAndRecord + mixWithOthers +
/// allowBluetoothA2DP, with the built-in mic preferred. That keeps other
/// music apps playing and keeps Bluetooth on stereo A2DP instead of the
/// mono call profile (HFP).
final class KaraokeEngine {
  var onLevel: ((Double, Double) -> Void)?
  var onRoute: ((String, String) -> Void)?
  var onState: ((Bool, Bool) -> Void)?
  var onError: ((String) -> Void)?

  private let audioEngine = AVAudioEngine()
  private let gainNode = AVAudioMixerNode()
  private let delayNode = AVAudioUnitDelay()
  private let reverb = AVAudioUnitReverb()
  private let dynamics: AVAudioUnitEffect

  private var gain: Float = 0.85
  private var reverbMix: Float = 0.06
  private var echoMix: Float = 0
  private var gateEnabled = true
  private var muted = false
  private var preset = "clean"
  private var wantRunning = false
  private var graphReady = false
  private var connectedRate: Double = 0
  private var connectedChannels: AVAudioChannelCount = 0
  private var hfpRetries = 0
  private var levelTimer: DispatchSourceTimer?
  private var observerTokens: [NSObjectProtocol] = []

  private enum Dynamics {
    static let threshold: AudioUnitParameterID = 0
    static let headRoom: AudioUnitParameterID = 1
    static let expansionRatio: AudioUnitParameterID = 2
    static let expansionThreshold: AudioUnitParameterID = 3
    static let attackTime: AudioUnitParameterID = 4
    static let releaseTime: AudioUnitParameterID = 5
    static let masterGain: AudioUnitParameterID = 6
    static let inputAmplitude: AudioUnitParameterID = 2000
  }

  deinit {
    levelTimer?.cancel()
    if audioEngine.isRunning {
      audioEngine.stop()
    }
    observerTokens.forEach { NotificationCenter.default.removeObserver($0) }
  }

  init() {
    let description = AudioComponentDescription(
      componentType: kAudioUnitType_Effect,
      componentSubType: kAudioUnitSubType_DynamicsProcessor,
      componentManufacturer: kAudioUnitManufacturer_Apple,
      componentFlags: 0,
      componentFlagsMask: 0
    )
    dynamics = AVAudioUnitEffect(audioComponentDescription: description)
    delayNode.delayTime = 0.12
    delayNode.feedback = 16
    delayNode.lowPassCutoff = 6500
    delayNode.wetDryMix = 0
    reverb.loadFactoryPreset(.smallRoom)
    reverb.wetDryMix = 0
    gainNode.outputVolume = 0.85
  }

  func startObserving() {
    installObservers()
    emitRoute()
  }

  func requestPermission(completion: @escaping (Bool) -> Void) {
    if #available(iOS 17.0, *) {
      AVAudioApplication.requestRecordPermission { granted in
        completion(granted)
      }
    } else {
      AVAudioSession.sharedInstance().requestRecordPermission { granted in
        completion(granted)
      }
    }
  }

  func permissionState() -> String {
    if #available(iOS 17.0, *) {
      switch AVAudioApplication.shared.recordPermission {
      case .granted:
        return "granted"
      case .denied:
        return "denied"
      default:
        return "undetermined"
      }
    }
    switch AVAudioSession.sharedInstance().recordPermission {
    case .granted:
      return "granted"
    case .denied:
      return "denied"
    default:
      return "undetermined"
    }
  }

  func status() -> [String: Any] {
    let route = readRoute()
    let session = AVAudioSession.sharedInstance()
    let buffer = session.ioBufferDuration
    return [
      "running": audioEngine.isRunning && wantRunning,
      "muted": muted,
      "route": route.kind,
      "routeName": route.name,
      "sampleRate": Int(session.sampleRate.rounded()),
      "lowLatency": wantRunning && buffer > 0 && buffer <= 0.012,
      "audioBackend": "AVAudioEngine",
      "latencyMs": buffer > 0 ? buffer * 1000.0 : 0.0,
      "mode": "direct",
      "wifiRole": "",
    ]
  }

  func start(_ options: [String: Any]) throws {
    guard permissionState() == "granted" else {
      throw KaraokeEngineError.permissionDenied
    }
    apply(options)
    installObservers()
    do {
      try configureSession()
      try ensureGraph()
      if !audioEngine.isRunning {
        audioEngine.prepare()
        try audioEngine.start()
      }
    } catch let error as KaraokeEngineError {
      throw error
    } catch {
      throw KaraokeEngineError.engineFailed(error.localizedDescription)
    }
    wantRunning = true
    startLevelTimer()
    emitRoute()
    emitState()
  }

  func stop() {
    wantRunning = false
    levelTimer?.cancel()
    levelTimer = nil
    if audioEngine.isRunning {
      audioEngine.stop()
    }
    tearDownGraph()
    try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    emitState()
    emitRoute()
  }

  func setGain(_ value: Double) {
    gain = Float(min(1.5, max(0, value)))
    applyGain()
  }

  func setReverb(_ value: Double) {
    reverbMix = Float(min(1, max(0, value)))
    reverb.wetDryMix = reverbMix * 100
  }

  func setEcho(_ value: Double) {
    echoMix = Float(min(1, max(0, value)))
    delayNode.wetDryMix = echoMix * 100
  }

  func setGate(_ enabled: Bool) {
    gateEnabled = enabled
    applyGate()
  }

  func setMuted(_ value: Bool) {
    muted = value
    applyGain()
    emitState()
  }

  func setPreset(_ name: String) {
    guard name == "clean" || name == "studio" || name == "hall" else {
      return
    }
    preset = name
    applyPresetSpace()
  }

  private func apply(_ options: [String: Any]) {
    if let value = options["gain"] as? Double {
      gain = Float(min(1.5, max(0, value)))
    }
    if let value = options["reverb"] as? Double {
      reverbMix = Float(min(1, max(0, value)))
    }
    if let value = options["echo"] as? Double {
      echoMix = Float(min(1, max(0, value)))
    }
    if let value = options["gate"] as? Bool {
      gateEnabled = value
    }
    if let value = options["preset"] as? String, value == "clean" || value == "studio" || value == "hall" {
      preset = value
    }
  }

  private func configureSession() throws {
    let session = AVAudioSession.sharedInstance()
    // mixWithOthers: do not pause Spotify / Apple Music / YouTube.
    // allowBluetoothA2DP without allowBluetooth: stereo output, not HFP.
    // defaultToSpeaker: loudspeaker when no cable or Bluetooth is connected.
    try session.setCategory(
      .playAndRecord,
      mode: .default,
      options: [.mixWithOthers, .allowBluetoothA2DP, .defaultToSpeaker]
    )
    try session.setPreferredSampleRate(48_000)
    try session.setPreferredIOBufferDuration(0.005)
    try session.setActive(true)
    preferBuiltInMicrophone()
    try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
  }

  private func preferBuiltInMicrophone() {
    let session = AVAudioSession.sharedInstance()
    guard let builtIn = session.availableInputs?.first(where: { $0.portType == .builtInMic }) else {
      return
    }
    try? session.setPreferredInput(builtIn)
  }

  private func ensureGraph() throws {
    let input = audioEngine.inputNode
    let format = input.outputFormat(forBus: 0)
    guard format.sampleRate > 0, format.channelCount > 0 else {
      throw KaraokeEngineError.noInput
    }
    if graphReady && connectedRate == format.sampleRate && connectedChannels == format.channelCount {
      applyEffects()
      return
    }
    tearDownGraph()
    audioEngine.attach(dynamics)
    audioEngine.attach(delayNode)
    audioEngine.attach(reverb)
    audioEngine.attach(gainNode)
    // Dynamics first so the meter and gate see the mic, then echo, reverb,
    // then the fader. Mute is the fader, so the meter keeps moving.
    audioEngine.connect(input, to: dynamics, format: format)
    audioEngine.connect(dynamics, to: delayNode, format: format)
    audioEngine.connect(delayNode, to: reverb, format: format)
    audioEngine.connect(reverb, to: gainNode, format: format)
    audioEngine.connect(gainNode, to: audioEngine.mainMixerNode, format: format)
    audioEngine.mainMixerNode.outputVolume = 1
    try? input.setVoiceProcessingEnabled(false)
    graphReady = true
    connectedRate = format.sampleRate
    connectedChannels = format.channelCount
    applyEffects()
  }

  private func tearDownGraph() {
    guard graphReady else {
      return
    }
    audioEngine.disconnectNodeInput(dynamics)
    audioEngine.disconnectNodeInput(delayNode)
    audioEngine.disconnectNodeInput(reverb)
    audioEngine.disconnectNodeInput(gainNode)
    audioEngine.disconnectNodeInput(audioEngine.mainMixerNode)
    audioEngine.detach(dynamics)
    audioEngine.detach(delayNode)
    audioEngine.detach(reverb)
    audioEngine.detach(gainNode)
    graphReady = false
  }

  private func applyEffects() {
    applyPresetSpace()
    reverb.wetDryMix = reverbMix * 100
    delayNode.wetDryMix = echoMix * 100
    applyGate()
    applyGain()
  }

  private func applyPresetSpace() {
    switch preset {
    case "studio":
      reverb.loadFactoryPreset(.mediumChamber)
      delayNode.delayTime = 0.16
      delayNode.feedback = 22
    case "hall":
      reverb.loadFactoryPreset(.largeHall2)
      delayNode.delayTime = 0.28
      delayNode.feedback = 32
    default:
      reverb.loadFactoryPreset(.smallRoom)
      delayNode.delayTime = 0.12
      delayNode.feedback = 14
    }
  }

  private func applyGate() {
    if gateEnabled {
      setDynamics(Dynamics.expansionRatio, 12)
      setDynamics(Dynamics.expansionThreshold, -42)
      setDynamics(Dynamics.releaseTime, 0.16)
    } else {
      setDynamics(Dynamics.expansionRatio, 1)
      setDynamics(Dynamics.expansionThreshold, -120)
      setDynamics(Dynamics.releaseTime, 0.05)
    }
    setDynamics(Dynamics.threshold, -12)
    setDynamics(Dynamics.headRoom, 6)
    setDynamics(Dynamics.attackTime, 0.004)
    applyGain()
  }

  private func applyGain() {
    guard graphReady else {
      gainNode.outputVolume = muted ? 0 : min(gain, 1)
      return
    }
    if muted || gain <= 0.001 {
      gainNode.outputVolume = 0
      setDynamics(Dynamics.masterGain, 0)
      return
    }
    gainNode.outputVolume = min(gain, 1)
    let boost = gain > 1 ? 20 * Float(log10(Double(gain))) : 0
    setDynamics(Dynamics.masterGain, boost)
  }

  private func setDynamics(_ parameter: AudioUnitParameterID, _ value: Float) {
    guard graphReady else {
      return
    }
    AudioUnitSetParameter(dynamics.audioUnit, parameter, kAudioUnitScope_Global, 0, value, 0)
  }

  private func startLevelTimer() {
    levelTimer?.cancel()
    let timer = DispatchSource.makeTimerSource(queue: .main)
    timer.schedule(deadline: .now(), repeating: .milliseconds(66))
    timer.setEventHandler { [weak self] in
      self?.emitLevel()
    }
    timer.resume()
    levelTimer = timer
  }

  private func emitLevel() {
    guard wantRunning, graphReady else {
      return
    }
    var amplitude: AudioUnitParameterValue = 0
    let status = AudioUnitGetParameter(
      dynamics.audioUnit,
      Dynamics.inputAmplitude,
      kAudioUnitScope_Global,
      0,
      &amplitude
    )
    guard status == noErr else {
      return
    }
    let clamped = Double(min(1, max(0, amplitude)))
    onLevel?(clamped, clamped)
  }

  private func installObservers() {
    guard observerTokens.isEmpty else {
      return
    }
    let center = NotificationCenter.default
    let session = AVAudioSession.sharedInstance()
    observerTokens.append(
      center.addObserver(
        forName: AVAudioSession.routeChangeNotification,
        object: session,
        queue: .main
      ) { [weak self] _ in
        self?.handleRouteChange()
      }
    )
    observerTokens.append(
      center.addObserver(
        forName: AVAudioSession.interruptionNotification,
        object: session,
        queue: .main
      ) { [weak self] note in
        self?.handleInterruption(note)
      }
    )
  }

  private func handleRouteChange() {
    preferBuiltInMicrophone()
    let route = readRoute()
    if route.kind == "bluetoothCall" && hfpRetries < 2 && wantRunning {
      hfpRetries += 1
      try? configureSession()
      if audioEngine.isRunning {
        let rate = audioEngine.inputNode.outputFormat(forBus: 0).sampleRate
        if rate > 0 && rate != connectedRate {
          try? restartGraph()
        }
      }
    } else if route.kind != "bluetoothCall" {
      hfpRetries = 0
      if wantRunning && audioEngine.isRunning {
        let rate = audioEngine.inputNode.outputFormat(forBus: 0).sampleRate
        if graphReady && rate > 0 && rate != connectedRate {
          try? restartGraph()
        }
      }
    }
    emitRoute()
  }

  private func restartGraph() throws {
    audioEngine.stop()
    tearDownGraph()
    try ensureGraph()
    audioEngine.prepare()
    try audioEngine.start()
  }

  private func handleInterruption(_ note: Notification) {
    guard
      let info = note.userInfo,
      let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
      let type = AVAudioSession.InterruptionType(rawValue: raw)
    else {
      return
    }
    switch type {
    case .began:
      if audioEngine.isRunning {
        audioEngine.pause()
      }
      onError?("Audio paused for an interruption, such as a phone call.")
      onState?(false, muted)
    case .ended:
      let optionsRaw = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
      let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
      guard wantRunning, options.contains(.shouldResume) else {
        return
      }
      do {
        try configureSession()
        try ensureGraph()
        try audioEngine.start()
        onState?(true, muted)
      } catch {
        wantRunning = false
        onError?(error.localizedDescription)
        onState?(false, muted)
      }
    default:
      break
    }
  }

  private func readRoute() -> (kind: String, name: String) {
    let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
    if let port = outputs.first(where: { $0.portType == .bluetoothA2DP || $0.portType == .bluetoothLE }) {
      return ("bluetooth", port.portName)
    }
    if let port = outputs.first(where: { $0.portType == .airPlay }) {
      return ("airplay", port.portName)
    }
    let wired: Set<AVAudioSession.Port> = [.headphones, .lineOut, .usbAudio, .HDMI, .carAudio]
    if let port = outputs.first(where: { wired.contains($0.portType) }) {
      return ("wired", port.portName)
    }
    if let port = outputs.first(where: { $0.portType == .bluetoothHFP }) {
      return ("bluetoothCall", port.portName)
    }
    if outputs.contains(where: { $0.portType == .builtInSpeaker || $0.portType == .builtInReceiver }) {
      return ("phone", "Phone speaker")
    }
    return ("unknown", outputs.first?.portName ?? "")
  }

  private func emitRoute() {
    let route = readRoute()
    onRoute?(route.kind, route.name)
  }

  private func emitState() {
    onState?(audioEngine.isRunning && wantRunning, muted)
  }
}
