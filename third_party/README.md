# third_party

## xray-core (local replace for AndroidLibXrayLite)

`AndroidLibXrayLite/go.mod` replaces `github.com/xtls/xray-core` with `./third_party/xray-core`.

Checkout used by the bundled AAR:

```bash
git clone https://github.com/autorepobot/xray-core.git third_party/xray-core
cd third_party/xray-core
git fetch --depth 1 origin 50c452881eb946e23c4098f9c288447e9d36345c
git checkout 50c452881eb946e23c4098f9c288447e9d36345c
```

ShrimpNG patch: `FindProcess` / `RegisterAndroidProcessFinder` take an extra `domain` argument filled from `ctx.GetTargetDomain()` in `app/router/condition.go`, so the connection journal can store sniffed hostnames instead of PTR guesses.

ShrimpNG patch: `common/protocol/quic/sniff.go` skips zero bytes after a parsed QUIC packet instead of
rejecting the datagram. Firefox (neqo) pads its Initial datagrams that way (RFC 9000 §14.1), and the
unpatched sniffer gave up on them, so Firefox's HTTP/3 flows were routed by IP and domain rules
(vk.com, geosite:VK, …) never matched. Upstream main still has the same check. Regression vectors
(real Firefox 156 Initial datagrams for vk.com) are in `sniff_test.go`.

Rebuild the AAR after changing the patch (Fedora; Go 1.26 and gomobile at the version pinned in
`AndroidLibXrayLite/go.mod`, installed with `go install golang.org/x/mobile/cmd/{gomobile,gobind}@<ver>`):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
export JAVA_HOME="$HOME/.jdks/jdk-17.0.20.1+1"
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
