import SwiftUI

@main
struct ZevClipApp: App {
    static let settingsWindowID = "settings"

    @StateObject private var receiver = ClipboardReceiver()
    @StateObject private var appSettings = AppSettings()

    var body: some Scene {
        MenuBarExtra {
            MenuBarContentView(receiver: receiver, appSettings: appSettings)
        } label: {
            Image("MenuBarIcon")
        }
        .menuBarExtraStyle(.menu)

        Window("ZevClip Settings", id: Self.settingsWindowID) {
            ContentView(receiver: receiver, appSettings: appSettings)
        }
        .defaultSize(width: 560, height: 720)
    }

}
