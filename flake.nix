{
  description = "pboard — privacy-preserving Android keyboard (dev environment)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" "37" ];
        buildToolsVersions = [ "36.0.0" "37.0.0" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis_playstore" ];
        abiVersions = [ "x86_64" ];
        includeNDK = false;
      };
      sdk = android.androidsdk;
      # NDK as a separate composition, so the SDK above (and its store path) stays exactly as it was.
      # Used to build arm64 native benchmark binaries (docs/voice-eval.md); exposed as ANDROID_NDK_HOME.
      ndk = (pkgs.androidenv.composeAndroidPackages {
        includeNDK = true;
        ndkVersions = [ "29.0.14206865" ];
      }).ndk-bundle;
      sdkRoot = "${sdk}/libexec/android-sdk";
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ sdk pkgs.jdk17 pkgs.imagemagick pkgs.ffmpeg pkgs.gradle pkgs.python3 ];
        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        JAVA_HOME = pkgs.jdk17.home;
        ANDROID_NDK_HOME = "${ndk}/libexec/android-sdk/ndk-bundle";
        # Use the Nix-patched aapt2 instead of the one Gradle downloads from Maven.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/37.0.0/aapt2";
        shellHook = ''
          export ANDROID_AVD_HOME="$HOME/.android/avd"
          export PATH="${sdkRoot}/emulator:${sdkRoot}/platform-tools:$PATH"
        '';
      };
    };
}
