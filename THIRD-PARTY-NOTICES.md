# Third-party code

Parts of Paramnezia are ported from open-source libraries and then changed. Each ported file
names its origin and what was changed at its top.

## ZXing — Apache License 2.0

Copyright 2007-2024 ZXing authors. https://github.com/zxing/zxing

Ported (to Kotlin, changed): `core/src/main/kotlin/net/paramnezia/core/qr/` — QrDetector.kt,
QrDecoder.kt, ReedSolomon.kt, QrEncoder.kt (Encoder, MaskUtil), QrTables.kt (version tables).

## JmDNS — Apache License 2.0

Copyright 2003-2005 Arthur van Hoff, Rick Blair and the JmDNS authors. https://github.com/jmdns/jmdns

Followed (behaviour, constants, names): `client/src/main/kotlin/net/paramnezia/app/lan/Mdns.kt`.

## JSch (mwiede fork) — BSD-style licence

Copyright (c) 2002-2018 ymnk, JCraft, Inc.; 2018- Matthias Wiedemann and contributors.
https://github.com/mwiede/jsch

Followed: the hybrid ML-KEM key exchange in `deploy/src/main/kotlin/net/paramnezia/deploy/ssh/SshClient.kt`.

## Bouncy Castle — MIT License

Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. https://www.bouncycastle.org

Ported (to Kotlin, changed): ML-KEM-768 and Keccak in `deploy/src/main/kotlin/net/paramnezia/deploy/ssh/MlKem.kt`.

## TweetNaCl — public domain

Daniel J. Bernstein, Bernard van Gastel, Wesley Janssen, Tanja Lange, Peter Schwabe, Sjaak Smetsers.
https://tweetnacl.cr.yp.to/

Ported: X25519 and Ed25519 in `deploy/src/main/kotlin/net/paramnezia/deploy/ssh/Curve25519.kt`.

---

The Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0.txt
