// swift-tools-version:5.9
import PackageDescription

// The iOS twin of kotlin/{engine,protocol,androidcore}'s worker surface.
// It speaks the documented event protocol directly (docs/protocol.md) and
// is byte-locked to the same cross-language vector corpus (docs/vectors/)
// as the Kotlin reference — the conformance tests regenerate the corpus
// and compare byte-for-byte, so the two implementations cannot drift.
let package = Package(
    name: "HpbCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "HpbCore", targets: ["HpbCore"])
    ],
    dependencies: [
        // Same libsecp256k1 binding generation nostr-sdk-ios pins: module
        // `secp256k1`, schnorr keys + raw secp256k1_ecdh both exposed.
        .package(url: "https://github.com/21-DOT-DEV/swift-secp256k1", exact: "0.12.2")
    ],
    targets: [
        .target(
            name: "HpbCore",
            dependencies: [.product(name: "secp256k1", package: "swift-secp256k1")]
        ),
        .testTarget(name: "HpbCoreTests", dependencies: ["HpbCore"]),
    ]
)
