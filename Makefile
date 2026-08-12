.PHONY: bindings android-so build run

bindings:
	cargo build -p slash_notes_core
	cargo run -p slash_notes_core --bin uniffi-bindgen -- generate \
		--library ./target/debug/libslash_notes_core.so \
		--language kotlin \
		--out-dir ./android/app/src/main/java

android-so:
	cargo ndk -t aarch64-linux-android -o ./android/app/src/main/jniLibs build --release -p slash_notes_core

run: bindings android-so
	cd android && ./gradlew installDebug --no-configuration-cache
	adb shell am start -n com.slashnote.app/.MainActivity
