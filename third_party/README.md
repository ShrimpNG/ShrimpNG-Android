# third_party

## xray-core (local replace for AndroidLibXrayLite)

`AndroidLibXrayLite/go.mod` replaces `github.com/xtls/xray-core` with `./third_party/xray-core`.

Source used by the bundled AAR: upstream XTLS/Xray-core **v26.10.21**, plain files (no `.git`):

```bash
curl -L https://github.com/XTLS/Xray-core/archive/refs/tags/v26.10.21.tar.gz | tar -xz
rsync -a --delete Xray-core-26.10.21/ third_party/xray-core/
```

ShrimpNG patch: `FindProcess` / `RegisterAndroidProcessFinder` take an extra `domain` argument
filled from `ctx.GetTargetDomain()` (in `app/router/condition.go` and the Lua `findProcess` helper in
`app/router/lua.go`), so the connection journal can store sniffed hostnames instead of PTR guesses.
Every platform's `FindProcess` and the tests calling it carry the extra argument.

Earlier ShrimpNG patches that upstream has since taken over, no longer carried:
- QUIC sniffer skipping zero padding after a packet (Firefox/neqo Initial datagrams). Upstream's
  rewritten sniffer handles it; our real Firefox 156 vectors for vk.com are kept in
  `common/protocol/quic/sniff_test.go` as a regression check.
- XHTTP path without a forced trailing `/` when `sessionIDPlacement` and `seqPlacement` are both off
  the path (upstream 1aabe7ea). Before it a file-like path (`…/1080p.mp4`) went out as
  `…/1080p.mp4/`, which an exact-match nginx/CDN location answers with 403.

Rebuild the AAR after changing the core (Fedora; Go 1.27.3 — the release's revoked-CA filtering
comes from that Go, and `GOTOOLCHAIN=go1.27.3` fetches it — and gomobile at the version pinned in `AndroidLibXrayLite/go.mod`, installed with
`go install golang.org/x/mobile/cmd/{gomobile,gobind}@<ver>`):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
export JAVA_HOME="$HOME/.jdks/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH" GOTOOLCHAIN=go1.27.3 GOFLAGS=-buildvcs=false
# gomobile only recognises platforms/android-NN, not sdkmanager's android-37.0
ln -sfn android-37.0 "$ANDROID_HOME/platforms/android-37"
cd AndroidLibXrayLite
gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' ./
cp libv2ray.aar ../V2rayNG/app/libs/
# keep jniLibs copies in sync
unzip -qo libv2ray.aar 'jni/*' -d /tmp/libv2ray_jni
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  cp -f "/tmp/libv2ray_jni/jni/$abi/libgojni.so" "../V2rayNG/app/libs/$abi/"
done
```
