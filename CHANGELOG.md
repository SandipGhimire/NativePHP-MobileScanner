# Changelog

All notable changes to `sghimire/mobile-scanner` are documented in this file.

## [1.0.3] - 2026-07-28

### Added

- Gallery button on the scanner overlay for picking an existing photo instead of the live camera, decoded on-device (Android Photo Picker + ML Kit, iOS `PHPickerViewController` + Vision) — no extra permissions required. Toggle it off per scan with `->gallery(false)`.
- Haptics, zoom control, focus-on-tap, and a configurable timeout for scan sessions.

## [1.0.2] - 2026-07-27

### Changed

- Deferred scan initialization on both Android and iOS so it now waits for the asynchronous camera permission request to resolve, instead of starting before permission was granted.

## [1.0.1] - 2026-07-27

### Changed

- Updated the Android `@OptIn` annotation to use the explicit `markerClass` syntax.

### Removed

- Hardcoded `version` field from `composer.json`.

## [1.0.0] - 2026-07-26

Initial release.

### Added

- Native QR code / barcode scanning for NativePHP Mobile apps, powered by CameraX + ML Kit (Android) and AVFoundation (iOS).
- Laravel facade (`Scanner`) and fluent, chainable scan builder in PHP and JS, plus a `scanner.d.ts` type declaration file.
- Single-shot and continuous (multi-scan) sessions, with the ability to stop an open session programmatically.
- `CodeScanned` and `Cancelled` Laravel events — usable with `Event::listen()` or bound directly to a Livewire method via `#[OnNative]`.
- Native Kotlin (Android) and Swift (iOS) bridge implementation, with no dependency on the paid `nativephp/mobile-scanner` plugin.
- MIT LICENSE and README with full usage documentation.
