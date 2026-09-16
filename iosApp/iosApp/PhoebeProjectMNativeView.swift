import Foundation
import UIKit
import ComposeApp

final class PhoebeProjectMNativeViewFactory: NSObject, IosProjectMNativeViewFactory {
    func create(
        presetPath: String?,
        presetData: String?,
        locked: Bool,
        playing: Bool,
        suspended: Bool
    ) -> UIView {
        let view = PhoebeProjectMHostView(frame: .zero)
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        apply(
            to: view,
            presetPath: presetPath,
            presetData: presetData,
            locked: locked,
            playing: playing,
            suspended: suspended
        )
        return view
    }

    func update(
        view: UIView,
        presetPath: String?,
        presetData: String?,
        locked: Bool,
        playing: Bool,
        suspended: Bool
    ) {
        guard let host = view as? PhoebeProjectMHostView else { return }
        apply(
            to: host,
            presetPath: presetPath,
            presetData: presetData,
            locked: locked,
            playing: playing,
            suspended: suspended
        )
    }

    func addPcm(view: UIView, samples: KotlinFloatArray, channels: Int32) {
        guard let host = view as? PhoebeProjectMHostView else { return }
        let count = Int(samples.size)
        guard count > 0 else { return }
        var buffer = [Float](repeating: 0, count: count)
        for i in 0..<count {
            buffer[i] = samples.get(index: Int32(i))
        }
        buffer.withUnsafeBufferPointer { ptr in
            host.addPcmSamples(ptr.baseAddress!, count: count, channels: Int(channels))
        }
    }

    func setSuspended(view: UIView, suspended: Bool) {
        (view as? PhoebeProjectMHostView)?.setSuspended(suspended)
    }

    func isNativeReady(view: UIView) -> Bool {
        (view as? PhoebeProjectMHostView)?.isNativeReady() ?? false
    }

    private func apply(
        to view: PhoebeProjectMHostView,
        presetPath: String?,
        presetData: String?,
        locked: Bool,
        playing: Bool,
        suspended: Bool
    ) {
        if let presetPath, !presetPath.isEmpty {
            view.setPresetPath(presetPath)
        } else if let presetData, !presetData.isEmpty {
            view.setPresetData(presetData)
        } else {
            view.setPresetPath("idle://")
        }
        view.setPresetLocked(locked)
        view.setPlaying(playing)
        view.setSuspended(suspended)
    }
}
