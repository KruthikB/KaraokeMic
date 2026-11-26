import ExpoModulesCore

public class KaraokeAudioModule: Module {
  private let engine = KaraokeEngine()

  public func definition() -> ModuleDefinition {
    Name("KaraokeAudio")

    Events("onLevel", "onRoute", "onState", "onError")

    OnCreate {
      self.engine.onLevel = { [weak self] rms, peak in
        self?.sendEvent("onLevel", ["rms": rms, "peak": peak])
      }
      self.engine.onRoute = { [weak self] kind, name in
        self?.sendEvent("onRoute", ["kind": kind, "name": name])
      }
      self.engine.onState = { [weak self] running, muted in
        self?.sendEvent("onState", ["running": running, "muted": muted])
      }
      self.engine.onError = { [weak self] message in
        self?.sendEvent("onError", ["message": message])
      }
      self.engine.startObserving()
    }

    OnDestroy {
      self.engine.onLevel = nil
      self.engine.onRoute = nil
      self.engine.onState = nil
      self.engine.onError = nil
    }

    Function("getPermission") { () -> String in
      self.engine.permissionState()
    }

    Function("getStatus") { () -> [String: Any] in
      self.engine.status()
    }

    Function("getWifiStats") { () -> [String: Any] in
      [
        "role": "",
        "connected": false,
        "advertising": false,
        "localAddress": "",
        "localPort": 0,
        "remoteAddress": "",
        "latencyMs": 0.0,
        "jitterMs": 0.0,
        "bufferMs": 0.0,
        "packetsLost": 0,
        "packetsReceived": 0,
        "audioBackend": "AVAudioEngine",
      ]
    }

    Function("getDiscoveredPeers") { () -> [[String: Any]] in
      []
    }

    Function("getLocalAddress") { () -> String in
      ""
    }

    Function("startDiscovery") {}

    Function("stopDiscovery") {}

    AsyncFunction("requestPermission") { (promise: Promise) in
      self.engine.requestPermission { granted in
        promise.resolve(granted)
      }
    }.runOnQueue(.main)

    AsyncFunction("start") { (options: [String: Any]) in
      try self.engine.start(options)
    }.runOnQueue(.main)

    AsyncFunction("stop") {
      self.engine.stop()
    }.runOnQueue(.main)

    Function("setMuted") { (muted: Bool) in
      self.engine.setMuted(muted)
    }

    Function("setGain") { (value: Double) in
      self.engine.setGain(value)
    }

    Function("setReverb") { (value: Double) in
      self.engine.setReverb(value)
    }

    Function("setEcho") { (value: Double) in
      self.engine.setEcho(value)
    }

    Function("setGate") { (enabled: Bool) in
      self.engine.setGate(enabled)
    }

    Function("setPreset") { (name: String) in
      self.engine.setPreset(name)
    }
  }
}
