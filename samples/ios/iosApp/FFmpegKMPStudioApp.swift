// SPDX-License-Identifier: Apache-2.0
import SwiftUI
import FFmpegKMPStudio

@main
struct FFmpegKMPStudioApp: App {
    var body: some Scene {
        WindowGroup {
            StudioView()
                .ignoresSafeArea(.keyboard)
        }
    }
}

private struct StudioView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
