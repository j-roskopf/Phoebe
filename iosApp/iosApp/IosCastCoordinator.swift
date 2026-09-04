import ComposeApp
import Foundation
import UIKit

#if canImport(GoogleCast)
import GoogleCast

final class IosCastCoordinator: NSObject {
    static let shared = IosCastCoordinator()

    private struct PendingMediaLoad {
        let requestId: Int64
        let descriptor: CastMediaDescriptor
        let startPositionMs: Int64
    }

    private var initialized = false
    private weak var hostViewController: UIViewController?
    private var castButton: GCKUICastButton?
    private var observedClient: GCKRemoteMediaClient?
    private var statusTimer: Timer?
    private var loadRequestIds: [Int: Int64] = [:]
    private var pendingMediaLoad: PendingMediaLoad?

    private override init() {
        super.init()
    }

    func initialize() {
        guard !initialized else { return }
        initialized = true

        if !GCKCastContext.isSharedInstanceInitialized() {
            let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
            let options = GCKCastOptions(discoveryCriteria: criteria)
            options.physicalVolumeButtonsWillControlDeviceVolume = true
            GCKCastContext.setSharedInstanceWith(options)
        }

        let context = GCKCastContext.sharedInstance()
        context.sessionManager.add(self)
        installBridgeCallbacks()
        IosCastBridge.shared.setAvailable(isAvailable: true, message: nil)
        attachRemoteClientIfNeeded()
        syncSessionState()
    }

    func attach(to viewController: UIViewController) {
        hostViewController = viewController
        installHiddenCastButtonIfNeeded()
    }

    private func installBridgeCallbacks() {
        IosCastBridge.shared.onShowDevicePicker = { [weak self] in
            KotlinBoolean(bool: self?.showDevicePicker() ?? false)
        }
        IosCastBridge.shared.onDisconnect = { [weak self] in
            self?.sessionManager.endSessionAndStopCasting(true)
        }
        IosCastBridge.shared.onHasConnectedSession = { [weak self] in
            KotlinBoolean(bool: self?.currentCastSession != nil)
        }
        IosCastBridge.shared.onLoadMedia = { [weak self] requestId, descriptor, startPositionMs in
            KotlinBoolean(
                bool: self?.loadMedia(
                    requestId: requestId.int64Value,
                    descriptor: descriptor,
                    startPositionMs: startPositionMs.int64Value
                ) ?? false
            )
        }
        IosCastBridge.shared.onTogglePlayPause = { [weak self] in
            KotlinBoolean(bool: self?.togglePlayPause() ?? false)
        }
        IosCastBridge.shared.onSeekTo = { [weak self] positionMs in
            KotlinBoolean(bool: self?.seek(to: positionMs.int64Value) ?? false)
        }
        IosCastBridge.shared.onReadVolume = { [weak self] in
            KotlinFloat(float: self?.readVolume() ?? 0.7)
        }
        IosCastBridge.shared.onSetVolume = { [weak self] volume in
            KotlinBoolean(bool: self?.setVolume(volume.floatValue) ?? false)
        }
    }

    private var sessionManager: GCKSessionManager {
        GCKCastContext.sharedInstance().sessionManager
    }

    private var currentCastSession: GCKCastSession? {
        sessionManager.currentCastSession
    }

    private func showDevicePicker() -> Bool {
        installHiddenCastButtonIfNeeded()
        guard let button = castButton else { return false }
        button.sendActions(for: .touchUpInside)
        return true
    }

    private func installHiddenCastButtonIfNeeded() {
        guard castButton == nil else { return }
        guard let hostView = hostViewController?.view ?? UIApplication.shared.keyWindow?.rootViewController?.view else {
            return
        }
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        button.alpha = 0.01
        button.isAccessibilityElement = false
        hostView.addSubview(button)
        castButton = button
    }

    private func loadMedia(requestId: Int64, descriptor: CastMediaDescriptor, startPositionMs: Int64) -> Bool {
        guard let url = URL(string: descriptor.castUrl) else {
            IosCastBridge.shared.loadFailed(requestId: requestId, message: "Couldn't load on Chromecast. Playing on this device.")
            return false
        }
        guard let client = currentCastSession?.remoteMediaClient else {
            pendingMediaLoad = PendingMediaLoad(
                requestId: requestId,
                descriptor: descriptor,
                startPositionMs: startPositionMs
            )
            startStatusTimer()
            return true
        }

        submitMediaLoad(
            requestId: requestId,
            descriptor: descriptor,
            startPositionMs: startPositionMs,
            url: url,
            client: client
        )
        return true
    }

    private func submitMediaLoad(
        requestId: Int64,
        descriptor: CastMediaDescriptor,
        startPositionMs: Int64,
        url: URL,
        client: GCKRemoteMediaClient
    ) {
        let metadata = GCKMediaMetadata(metadataType: .musicTrack)
        metadata.setString(descriptor.title, forKey: kGCKMetadataKeyTitle)
        metadata.setString(descriptor.artist, forKey: kGCKMetadataKeyArtist)
        metadata.setString(descriptor.album, forKey: kGCKMetadataKeyAlbumTitle)
        if let thumb = descriptor.thumbUrl,
           (thumb.hasPrefix("http://") || thumb.hasPrefix("https://")),
           let thumbUrl = URL(string: thumb) {
            metadata.addImage(GCKImage(url: thumbUrl, width: 600, height: 600))
        }

        let builder = GCKMediaInformationBuilder(contentURL: url)
        builder.streamType = .buffered
        builder.streamDuration = TimeInterval(descriptor.durationMs) / 1000.0
        builder.contentType = descriptor.contentType
        builder.metadata = metadata
        builder.customData = customData(for: descriptor)

        let loadBuilder = GCKMediaLoadRequestDataBuilder()
        loadBuilder.mediaInformation = builder.build()
        loadBuilder.autoplay = true
        loadBuilder.startTime = TimeInterval(startPositionMs) / 1000.0

        let request = client.loadMedia(with: loadBuilder.build())
        loadRequestIds[Int(request.requestID)] = requestId
        request.delegate = self
        attach(remoteClient: client)
        startStatusTimer()
    }

    private func customData(for descriptor: CastMediaDescriptor) -> [String: Any] {
        var data: [String: Any] = [
            "phoebeTrackId": descriptor.trackId,
            "title": descriptor.title,
            "artist": descriptor.artist,
            "album": descriptor.album,
            "durationMs": descriptor.durationMs,
            "streamUrl": descriptor.streamUrl,
            "castUrl": descriptor.castUrl,
            "downloadUrl": descriptor.downloadUrl,
            "audioCodec": descriptor.audioCodec ?? "",
        ]
        if let thumbUrl = descriptor.thumbUrl {
            data["thumbUrl"] = thumbUrl
        }
        if let filepath = descriptor.filepath {
            data["filepath"] = filepath
        }
        return data
    }

    private func togglePlayPause() -> Bool {
        guard let client = currentCastSession?.remoteMediaClient else { return false }
        let state = client.mediaStatus?.playerState
        if state == .playing || state == .buffering {
            client.pause()
        } else {
            client.play()
        }
        syncRemoteStatus()
        return true
    }

    private func seek(to positionMs: Int64) -> Bool {
        guard let client = currentCastSession?.remoteMediaClient else { return false }
        let options = GCKMediaSeekOptions()
        options.interval = TimeInterval(max(positionMs, 0)) / 1000.0
        options.relative = false
        client.seek(with: options)
        syncRemoteStatus()
        return true
    }

    private func readVolume() -> Float {
        currentCastSession?.currentDeviceVolume ?? 0.7
    }

    private func setVolume(_ volume: Float) -> Bool {
        guard let session = currentCastSession else { return false }
        let normalized = max(0, min(volume, 1))
        session.setDeviceVolume(normalized)
        IosCastBridge.shared.castVolumeChanged(volume: normalized)
        return true
    }

    private func syncSessionState() {
        guard let session = currentCastSession else {
            stopStatusTimer()
            IosCastBridge.shared.sessionEnded()
            return
        }
        attach(remoteClient: session.remoteMediaClient)
        IosCastBridge.shared.sessionStarted(
            deviceName: session.device.friendlyName,
            receiverHasMedia: session.remoteMediaClient?.mediaStatus?.mediaInformation != nil
        )
        syncRemoteStatus()
        startStatusTimer()
    }

    private func attachRemoteClientIfNeeded() {
        attach(remoteClient: currentCastSession?.remoteMediaClient)
    }

    private func attach(remoteClient: GCKRemoteMediaClient?) {
        if observedClient === remoteClient { return }
        observedClient?.remove(self)
        observedClient = remoteClient
        remoteClient?.add(self)
        remoteClient?.requestStatus()
        flushPendingMediaLoadIfPossible()
    }

    private func startStatusTimer() {
        guard statusTimer == nil else { return }
        statusTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.flushPendingMediaLoadIfPossible()
            self?.syncRemoteStatus()
        }
    }

    private func stopStatusTimer() {
        statusTimer?.invalidate()
        statusTimer = nil
        observedClient?.remove(self)
        observedClient = nil
    }

    private func flushPendingMediaLoadIfPossible() {
        guard let pending = pendingMediaLoad else { return }
        guard let client = currentCastSession?.remoteMediaClient else { return }
        guard let url = URL(string: pending.descriptor.castUrl) else {
            pendingMediaLoad = nil
            IosCastBridge.shared.loadFailed(
                requestId: pending.requestId,
                message: "Couldn't load on Chromecast. Playing on this device."
            )
            return
        }
        pendingMediaLoad = nil
        submitMediaLoad(
            requestId: pending.requestId,
            descriptor: pending.descriptor,
            startPositionMs: pending.startPositionMs,
            url: url,
            client: client
        )
    }

    private func syncRemoteStatus() {
        guard let session = currentCastSession, let client = session.remoteMediaClient else { return }
        guard let status = client.mediaStatus, let mediaInfo = status.mediaInformation else {
            IosCastBridge.shared.remoteMediaStatus(
                trackId: nil,
                title: nil,
                artist: nil,
                album: nil,
                durationMs: 0,
                streamUrl: nil,
                castUrl: nil,
                downloadUrl: nil,
                thumbUrl: nil,
                filepath: nil,
                audioCodec: nil,
                positionMs: 0,
                isPlaying: false,
                isBuffering: false,
                deviceName: session.device.friendlyName
            )
            return
        }

        let custom = mediaInfo.customData as? [String: Any]
        let metadata = mediaInfo.metadata
        let title = stringValue(custom, "title") ?? metadata?.string(forKey: kGCKMetadataKeyTitle)
        let artist = stringValue(custom, "artist") ?? metadata?.string(forKey: kGCKMetadataKeyArtist)
        let album = stringValue(custom, "album") ?? metadata?.string(forKey: kGCKMetadataKeyAlbumTitle)
        let thumbUrl = stringValue(custom, "thumbUrl") ?? firstImageUrl(from: metadata)
        let durationMs = int64Value(custom, "durationMs") ?? Int64(max(mediaInfo.streamDuration, 0) * 1000)
        let positionMs = Int64(max(client.approximateStreamPosition(), 0) * 1000)

        IosCastBridge.shared.remoteMediaStatus(
            trackId: stringValue(custom, "phoebeTrackId"),
            title: title,
            artist: artist,
            album: album,
            durationMs: durationMs,
            streamUrl: stringValue(custom, "streamUrl") ?? mediaInfo.contentID,
            castUrl: stringValue(custom, "castUrl") ?? mediaInfo.contentID,
            downloadUrl: stringValue(custom, "downloadUrl"),
            thumbUrl: thumbUrl,
            filepath: stringValue(custom, "filepath"),
            audioCodec: stringValue(custom, "audioCodec"),
            positionMs: positionMs,
            isPlaying: status.playerState == .playing,
            isBuffering: status.playerState == .buffering,
            deviceName: session.device.friendlyName
        )
        IosCastBridge.shared.castVolumeChanged(volume: session.currentDeviceVolume)
    }

    private func stringValue(_ data: [String: Any]?, _ key: String) -> String? {
        guard let value = data?[key] else { return nil }
        if let string = value as? String, !string.isEmpty {
            return string
        }
        if let number = value as? NSNumber {
            return number.stringValue
        }
        return nil
    }

    private func int64Value(_ data: [String: Any]?, _ key: String) -> Int64? {
        guard let value = data?[key] else { return nil }
        if let number = value as? NSNumber {
            return number.int64Value
        }
        if let string = value as? String {
            return Int64(string)
        }
        return nil
    }

    private func firstImageUrl(from metadata: GCKMediaMetadata?) -> String? {
        guard let image = metadata?.images().first as? GCKImage else { return nil }
        return image.url.absoluteString
    }
}

extension IosCastCoordinator: GCKSessionManagerListener {
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
        syncSessionState()
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) {
        syncSessionState()
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didSuspend session: GCKSession, with reason: GCKConnectionSuspendReason) {
        IosCastBridge.shared.sessionSuspended()
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) {
        stopStatusTimer()
        IosCastBridge.shared.sessionEnded()
    }

    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKSession, withError error: Error) {
        IosCastBridge.shared.sessionStartFailed(message: error.localizedDescription)
    }

    func sessionManager(_ sessionManager: GCKSessionManager, session: GCKSession, didReceiveDeviceVolume volume: Float, muted: Bool) {
        IosCastBridge.shared.castVolumeChanged(volume: volume)
    }
}

extension IosCastCoordinator: GCKRemoteMediaClientListener {
    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
        syncRemoteStatus()
    }

    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaMetadata: GCKMediaMetadata?) {
        syncRemoteStatus()
    }
}

extension IosCastCoordinator: GCKRequestDelegate {
    func requestDidComplete(_ request: GCKRequest) {
        guard let requestId = loadRequestIds.removeValue(forKey: Int(request.requestID)) else { return }
        IosCastBridge.shared.loadSucceeded(requestId: requestId)
        syncRemoteStatus()
    }

    func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        guard let requestId = loadRequestIds.removeValue(forKey: Int(request.requestID)) else { return }
        IosCastBridge.shared.loadFailed(
            requestId: requestId,
            message: error.localizedDescription.isEmpty
                ? "Couldn't load on Chromecast. Playing on this device."
                : "Couldn't load on Chromecast. \(error.localizedDescription)"
        )
    }
}

#else

final class IosCastCoordinator {
    static let shared = IosCastCoordinator()

    private init() {}

    func initialize() {
        IosCastBridge.shared.setAvailable(
            isAvailable: false,
            message: "Chromecast on iOS needs the Google Cast SDK in the host app."
        )
    }

    func attach(to viewController: UIViewController) {}
}

#endif
