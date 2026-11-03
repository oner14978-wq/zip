# Minecraft Launcher (Capacitor + MCNative)

Compilar (precisa de internet, JDK 17 e Android SDK): `./build-apk.sh`
Ou envie a pasta para um repositório GitHub e rode o workflow "Build APK".
APK: `android/app/build/outputs/apk/debug/app-debug.apk` (assinado com chave debug, instalável).

Plugin: android/app/src/main/java/com/mclauncher/app/MCNativePlugin.kt
Registro: MainActivity.kt -> registerPlugin(MCNativePlugin::class.java)
Métodos: ping, clearCache, downloadAll (+evento "progress"), verify, prepareJava, launch.

Java: coloque `jre21-aarch64.zip` (bin/java na raiz) em android/app/src/main/assets/runtime/ antes de compilar.
Sem ele, prepareJava/launch retornam erro explícito (nunca sucesso falso).
