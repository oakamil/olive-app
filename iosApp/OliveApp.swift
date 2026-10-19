// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

import SwiftUI

@main
struct OliveApp: App {
    init() {
        MotionService.registerStorageCallbacks()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
