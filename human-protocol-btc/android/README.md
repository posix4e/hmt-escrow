# HPB Android

The thin Compose shell over `:androidcore` (see `../kotlin`), following the
architecture invariant: **all behavior lives in the JVM-tested libraries;
the app is a view.** Worker (jobs/claim/solve/earnings), witness
verification (recomputes exactly what a co-sign PSBT must pay before the
user approves), and the distributed dashboard — all fed by relays only.

```sh
# needs the Android SDK (built in CI on every push)
gradle :app:assembleDebug
```

Composite build: `settings.gradle.kts` includes `../kotlin` and substitutes
`org.hpb:androidcore`. libsecp256k1 comes from
`secp256k1-kmp-jni-android`; NIP-44's ChaCha20 needs minSdk 28.

Roadmap: on-device CBF wallet (bdk-android + kyoto) for server-free payout
verification and witness PSBT signing bound to the verified summary.
