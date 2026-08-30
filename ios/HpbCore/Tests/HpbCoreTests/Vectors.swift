import Foundation

/// The vendored cross-language vector files live at the repo root; resolve
/// them relative to this source file so `swift test` needs no resource
/// copying and both implementations pin the SAME bytes.
func vectorsUrl(_ name: String, from file: String = #filePath) -> URL {
    URL(fileURLWithPath: file)
        .deletingLastPathComponent() // Vectors.swift -> HpbCoreTests/
        .deletingLastPathComponent() // Tests/
        .deletingLastPathComponent() // HpbCore/
        .deletingLastPathComponent() // ios/
        .deletingLastPathComponent() // repo root
        .appendingPathComponent("docs/vectors")
        .appendingPathComponent(name)
}
