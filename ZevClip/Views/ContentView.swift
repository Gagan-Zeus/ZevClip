import SwiftUI

struct ContentView: View {
    @ObservedObject var receiver: ClipboardReceiver
    @ObservedObject var macClipboardWatcher: MacClipboardWatcher
    @ObservedObject var androidClipboardSender: AndroidClipboardSender
    @ObservedObject var appSettings: AppSettings

    private var isSyncRunning: Bool {
        receiver.status == .running && macClipboardWatcher.isRunning
    }

    private var syncColor: Color {
        if isSyncRunning {
            return .green
        }

        if receiver.status == .starting || androidClipboardSender.isSending || androidClipboardSender.isDiscovering {
            return .orange
        }

        if case .failed = receiver.status {
            return .red
        }

        return .secondary
    }

    private var syncTitle: String {
        isSyncRunning ? "Clipboard Sync On" : "Clipboard Sync Off"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 10) {
                    Circle()
                        .fill(syncColor)
                        .frame(width: 10, height: 10)

                    Text(syncTitle)
                        .font(.title2.bold())

                    Spacer()

                    Button(isSyncRunning ? "Stop Clipboard Sync" : "Start Clipboard Sync") {
                        if isSyncRunning {
                            stopClipboardSync()
                        } else {
                            startClipboardSync()
                        }
                    }
                    .keyboardShortcut("r", modifiers: [.command])
                }

                Text("One control handles Android to Mac and Mac to Android clipboard sharing.")
                    .foregroundStyle(.secondary)

                VStack(alignment: .leading, spacing: 10) {
                    Text("Status")
                        .font(.headline)

                    LabeledContent("Mac receiver") {
                        Text(receiver.status.title)
                            .foregroundStyle(receiver.status == .running ? .green : syncColor)
                    }

                    LabeledContent("Mac watcher") {
                        Text(macClipboardWatcher.isRunning ? "Watching" : "Stopped")
                            .foregroundStyle(macClipboardWatcher.isRunning ? .green : .secondary)
                    }

                    LabeledContent("Android") {
                        if let endpoint = androidClipboardSender.resolvedEndpoint {
                            Text(endpoint.displayAddress)
                                .textSelection(.enabled)
                        } else {
                            Text("Not discovered")
                                .foregroundStyle(.secondary)
                        }
                    }

                    LabeledContent("Sender") {
                        Text(androidClipboardSender.status)
                            .foregroundStyle(androidClipboardSender.isDiscovering ? .orange : .secondary)
                    }

                    Text(macClipboardWatcher.status)
                        .foregroundStyle(.secondary)
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Pairing")
                        .font(.headline)

                    Text("Scan this QR in the Android app once, then use Start Clipboard Sync on both devices.")
                        .foregroundStyle(.secondary)

                    PairingQRCodeView(token: receiver.pairingToken, deviceId: receiver.deviceId)

                    Text(receiver.pairingToken.isEmpty ? "Token unavailable" : receiver.pairingToken)
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                        .lineLimit(2)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))

                    HStack {
                        Button("Regenerate Pairing Token") {
                            receiver.regeneratePairingToken()
                        }

                        Button("Rediscover Android") {
                            androidClipboardSender.rediscoverAndroidReceiver()
                        }
                        .disabled(androidClipboardSender.isDiscovering)
                    }
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Recent Activity")
                        .font(.headline)

                    Text("From Android: \(receiver.lastReceivedText ?? "No clipboard text received yet.")")
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .foregroundStyle(receiver.lastReceivedText == nil ? .secondary : .primary)
                        .textSelection(.enabled)
                        .padding(12)
                        .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))

                    Text("To Android: \(androidClipboardSender.lastSentText ?? "No Mac clipboard text sent yet.")")
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .foregroundStyle(androidClipboardSender.lastSentText == nil ? .secondary : .primary)
                        .textSelection(.enabled)
                        .padding(12)
                        .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Application")
                        .font(.headline)

                    Toggle(
                        "Show in menu bar",
                        isOn: Binding(
                            get: { appSettings.showMenuBarIcon },
                            set: { appSettings.setShowMenuBarIcon($0) }
                        )
                    )

                    Toggle(
                        "Launch at Login",
                        isOn: Binding(
                            get: { appSettings.launchAtLoginEnabled },
                            set: { appSettings.setLaunchAtLoginEnabled($0) }
                        )
                    )

                    Text(appSettings.launchAtLoginStatus)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(24)
        }
        .frame(minWidth: 560, minHeight: 640)
    }

    private func startClipboardSync() {
        receiver.startServer()
        macClipboardWatcher.start()
        androidClipboardSender.rediscoverAndroidReceiver()
    }

    private func stopClipboardSync() {
        receiver.stopServer()
        macClipboardWatcher.stop()
    }
}
