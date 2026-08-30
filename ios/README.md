# ios

The native iOS worker app: SwiftUI over `HpbCore`, a from-scratch Swift
implementation of the protocol's worker surface (schnorr events, NIP-44,
offers/claims/submissions/receipts, the deterministic assignment reducer,
a NIP-01 relay client). No chain access — a worker needs only a key and
relays; receipts carry txids the wallet layer verifies itself.

Conformance: `HpbCore`'s tests regenerate the cross-language vector
corpus (`docs/vectors/`) with the same fixed keys and timestamps as the
Kotlin reference and compare byte-for-byte — the two implementations
cannot drift without failing both suites.

- `HpbCore/` — Swift package (library + conformance tests): `swift test`
- `App/` — the app. Generate the project and run:
  `brew install xcodegen && cd App && xcodegen generate`, open
  `HpbLabeler.xcodeproj`. Set your relays and payout address in the app;
  `--demo` launches an in-memory job for UI work and the CI screenshot
  test.

CI builds both on a macOS runner: the vector conformance suite, then the
app + UI smoke test on the iPhone simulator, with stage screenshots
exported as workflow artifacts.
