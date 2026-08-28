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

Rebuild the AAR after changing the patch:

```bash
export PATH="$(brew --prefix go)/bin:$HOME/go/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/<version>"
cd AndroidLibXrayLite
go mod tidy
gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' ./
cp libv2ray.aar ../V2rayNG/app/libs/
# keep jniLibs copies in sync
unzip -qo libv2ray.aar 'jni/*' -d /tmp/libv2ray_jni
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  cp -f "/tmp/libv2ray_jni/jni/$abi/libgojni.so" "../V2rayNG/app/libs/$abi/"
done
```
