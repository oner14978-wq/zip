package com.mclauncher.app

import android.os.Build
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.PluginMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

@CapacitorPlugin(name = "MCNative")
class MCNativePlugin : Plugin() {

    private val io = Executors.newCachedThreadPool()
    private val dl = Executors.newFixedThreadPool(4)

    // ---------- util ----------
    private fun root(): File = context.getExternalFilesDir(null) ?: context.filesDir

    private fun safe(rel: String): File {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.split("/").any { it == ".." })
            throw IllegalArgumentException("Caminho inválido: $rel")
        val r = root().canonicalFile
        val f = File(r, rel).canonicalFile
        if (!f.path.startsWith(r.path + File.separator)) throw IllegalArgumentException("Caminho fora da pasta do app: $rel")
        return f
    }

    private fun sha1Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(65536)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun run(call: PluginCall, block: () -> JSObject) {
        io.execute {
            try { call.resolve(block()) }
            catch (e: Throwable) { call.reject(e.message ?: e.toString()) }
        }
    }

    private fun checkDir(dir: String?): String {
        if (dir.isNullOrEmpty() || dir.contains("..") || dir.contains("\\") || dir.startsWith("/"))
            throw IllegalArgumentException("Nome de pasta inválido")
        return dir
    }

    // ---------- ping / diagnóstico ----------
    @PluginMethod
    fun ping(call: PluginCall) {
        val r = JSObject()
        r.put("ok", true)
        r.put("plugin", "MCNative")
        r.put("version", "1.0.0")
        r.put("echo", call.getString("msg") ?: "")
        r.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        r.put("abis", Build.SUPPORTED_ABIS.joinToString(","))
        r.put("sdk", Build.VERSION.SDK_INT)
        r.put("root", root().absolutePath)
        call.resolve(r)
    }

    // ---------- clearCache ----------
    @PluginMethod
    fun clearCache(call: PluginCall) = run(call) {
        val dir = checkDir(call.getString("dir"))
        var freed = 0L
        fun wipe(f: File, keepRoot: Boolean) {
            f.listFiles()?.forEach { c ->
                if (c.isDirectory) wipe(c, false) else { freed += c.length(); c.delete() }
            }
            if (!keepRoot) f.delete()
        }
        wipe(context.cacheDir, true)
        File(root(), "$dir/.cache").takeIf { it.isDirectory }?.let { wipe(it, true) }
        // arquivos parciais de downloads interrompidos
        safe(dir).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach { freed += it.length(); it.delete() }
        JSObject().apply { put("ok", true); put("freedBytes", freed) }
    }

    // ---------- downloadAll ----------
    @PluginMethod
    fun downloadAll(call: PluginCall) {
        val dir = try { checkDir(call.getString("dir")) } catch (e: Exception) { return call.reject(e.message) }
        val files = call.getArray("files") ?: return call.reject("files ausente")
        val overwrite = call.getBoolean("overwrite") ?: false
        io.execute {
            try {
                val bytes = AtomicLong(0); val done = AtomicInteger(0)
                val failed = JSONArray()
                var lastEmit = 0L
                val lock = Any()
                fun emit(force: Boolean) {
                    synchronized(lock) {
                        val now = System.currentTimeMillis()
                        if (force || now - lastEmit > 250) {
                            lastEmit = now
                            notifyListeners("progress", JSObject().apply {
                                put("bytes", bytes.get()); put("done", done.get())
                            })
                        }
                    }
                }
                val latch = java.util.concurrent.CountDownLatch(files.length())
                for (i in 0 until files.length()) {
                    val o = files.getJSONObject(i)
                    dl.execute {
                        val path = o.optString("path")
                        try {
                            val dest = safe(path)
                            if (o.has("inline")) {
                                dest.parentFile?.mkdirs(); dest.writeText(o.getString("inline"))
                            } else {
                                val url = o.getString("url")
                                if (!url.startsWith("https://")) throw IOException("URL não-HTTPS bloqueada")
                                val size = o.optLong("size", 0); val sha = o.optString("sha1", "")
                                if (!overwrite && dest.isFile && (size <= 0 || dest.length() == size) &&
                                    (sha.isEmpty() || sha1Of(dest) == sha)) {
                                    bytes.addAndGet(dest.length())
                                } else {
                                    var ok = false; var err: Exception? = null
                                    for (attempt in 1..2) {
                                        var counted = 0L
                                        try {
                                            fetch(url, dest) { n -> counted += n; bytes.addAndGet(n); emit(false) }
                                            if (sha.isNotEmpty() && sha1Of(dest) != sha) { dest.delete(); throw IOException("SHA-1 divergente") }
                                            ok = true; break
                                        } catch (e: Exception) { err = e; bytes.addAndGet(-counted) }
                                    }
                                    if (!ok) throw err ?: IOException("falha")
                                }
                            }
                        } catch (e: Exception) {
                            synchronized(failed) { failed.put(path) }
                        } finally {
                            done.incrementAndGet(); emit(false); latch.countDown()
                        }
                    }
                }
                latch.await()
                emit(true)
                call.resolve(JSObject().apply {
                    put("ok", failed.length() == 0); put("failed", failed); put("bytes", bytes.get())
                })
            } catch (e: Throwable) { call.reject(e.message ?: e.toString()) }
        }
    }

    private fun fetch(url: String, dest: File, onBytes: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var c: HttpURLConnection? = null
        try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MCNative/1.0")
            }
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { ins ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(65536)
                    while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); onBytes(n.toLong()) }
                }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename falhou")
        } finally { c?.disconnect(); if (part.exists()) part.delete() }
    }

    // ---------- verify ----------
    @PluginMethod
    fun verify(call: PluginCall) = run(call) {
        val dir = checkDir(call.getString("dir"))
        val doSha = call.getBoolean("sha1") ?: false
        val quick = call.getBoolean("quick") ?: false
        val missing = JSONArray()
        fun check(path: String, size: Long, hash: String?, useSha: Boolean) {
            val f = safe(path)
            val bad = !f.isFile || (size > 0 && f.length() != size) ||
                (useSha && !hash.isNullOrEmpty() && sha1Of(f) != hash)
            if (bad) missing.put(path)
        }
        val files = call.getArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val o = files.getJSONObject(i)
                check(o.getString("path"), o.optLong("size", 0), o.optString("sha1", ""), doSha)
            }
        } else {
            val ver = call.getString("version") ?: throw IllegalArgumentException("version ausente")
            if (!Regex("^[\\w.\\-]+$").matches(ver)) throw IllegalArgumentException("versão inválida")
            val jsonPath = "$dir/versions/$ver/$ver.json"
            val jarPath = "$dir/versions/$ver/$ver.jar"
            val jf = safe(jsonPath)
            if (!jf.isFile) { missing.put(jsonPath) }
            if (!safe(jarPath).isFile) { missing.put(jarPath) }
            if (!quick && jf.isFile) {
                val vj = JSONObject(jf.readText())
                val cl = vj.getJSONObject("downloads").getJSONObject("client")
                if (missing.length() == 0) check(jarPath, cl.optLong("size"), cl.optString("sha1"), doSha)
                for (l in libraries(vj, dir)) check(l.first, l.second, l.third, doSha)
                val ai = vj.getJSONObject("assetIndex")
                val aiPath = "$dir/assets/indexes/${ai.getString("id")}.json"
                check(aiPath, ai.optLong("size"), ai.optString("sha1"), doSha)
                val aif = safe(aiPath)
                if (aif.isFile) {
                    val objs = JSONObject(aif.readText()).getJSONObject("objects")
                    val it = objs.keys()
                    while (it.hasNext()) {
                        val o = objs.getJSONObject(it.next()); val h = o.getString("hash")
                        check("$dir/assets/objects/${h.substring(0, 2)}/$h", o.optLong("size"), h, false)
                    }
                }
            }
        }
        JSObject().apply { put("ok", missing.length() == 0); put("missing", missing) }
    }

    private fun rulesOk(rules: JSONArray?): Boolean {
        if (rules == null) return true
        var ok = false
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val o = r.optJSONObject("os")
            var m = o == null || ((!o.has("name") || o.getString("name") == "linux") &&
                (!o.has("arch") || Regex("^(arm64|aarch64)$").matches(o.getString("arch"))))
            val f = r.optJSONObject("features")
            if (f != null) { val it = f.keys(); while (it.hasNext()) { val k = it.next(); if (f.getBoolean(k) != (k == "has_custom_resolution")) m = false } }
            if (m) ok = r.getString("action") == "allow"
        }
        return ok
    }

    /** (caminho relativo, tamanho, sha1) das bibliotecas permitidas para linux/arm64. */
    private fun libraries(vj: JSONObject, dir: String): List<Triple<String, Long, String>> {
        val out = ArrayList<Triple<String, Long, String>>()
        val libs = vj.getJSONArray("libraries")
        for (i in 0 until libs.length()) {
            val l = libs.getJSONObject(i)
            if (!rulesOk(l.optJSONArray("rules"))) continue
            val d = l.optJSONObject("downloads") ?: continue
            d.optJSONObject("artifact")?.let { out.add(Triple("$dir/libraries/" + it.getString("path"), it.optLong("size"), it.optString("sha1"))) }
            val nat = l.optJSONObject("natives")?.optString("linux", "")
            if (!nat.isNullOrEmpty()) d.optJSONObject("classifiers")?.optJSONObject(nat.replace("\${arch}", "64"))?.let {
                out.add(Triple("$dir/libraries/" + it.getString("path"), it.optLong("size"), it.optString("sha1")))
            }
        }
        return out
    }

    // ---------- prepareJava ----------
    private fun javaHome(major: Int) = File(context.filesDir, "runtime/jre$major")

    @PluginMethod
    fun prepareJava(call: PluginCall) = run(call) {
        val major = call.getInt("major") ?: 21
        val res = JSObject()
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            return@run res.apply { put("ok", false); put("error", "Dispositivo sem ARM64 (ABIs: ${Build.SUPPORTED_ABIS.joinToString()})") }
        }
        val home = javaHome(major)
        if (!File(home, "bin/java").isFile) {
            val name = "jre$major-aarch64.zip"
            val has = context.assets.list("runtime")?.contains(name) == true
            if (has) {
                home.deleteRecursively(); home.mkdirs()
                ZipInputStream(context.assets.open("runtime/$name")).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val t = File(home, e.name).canonicalFile
                        if (!t.path.startsWith(home.canonicalPath + File.separator)) throw IOException("zip inseguro")
                        if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); FileOutputStream(t).use { z.copyTo(it) } }
                        e = z.nextEntry
                    }
                }
                File(home, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
                File(home, "lib/jspawnhelper").takeIf { it.exists() }?.setExecutable(true, false)
            }
        }
        val java = File(home, "bin/java")
        if (!java.isFile) {
            return@run res.apply {
                put("ok", false)
                put("error", "Runtime Java $major ARM64 não encontrado. Inclua app/src/main/assets/runtime/jre$major-aarch64.zip (com bin/java na raiz) e recompile o APK.")
            }
        }
        java.setExecutable(true, false)
        res.apply { put("ok", true); put("home", home.absolutePath) }
    }

    // ---------- launch ----------
    @PluginMethod
    fun launch(call: PluginCall) = run(call) {
        val dir = checkDir(call.getString("dir"))
        val ver = call.getString("version") ?: throw IllegalArgumentException("version ausente")
        if (!Regex("^[\\w.\\-]+$").matches(ver)) throw IllegalArgumentException("versão inválida")
        val user = call.getString("username") ?: "Player"
        val ram = call.getInt("ramMb") ?: 2048
        val w = call.getInt("width") ?: 854; val h = call.getInt("height") ?: 480
        val renderer = call.getString("renderer") ?: "gl4es"
        val lang = call.getString("lang") ?: "pt_br"
        val java = File(javaHome(21), "bin/java")
        if (!java.isFile) throw IllegalStateException("Runtime Java 21 ARM64 ausente (chame prepareJava).")

        val gameDir = safe(dir); val rootAbs = root().absolutePath
        val vj = JSONObject(safe("$dir/versions/$ver/$ver.json").readText())
        val cp = ArrayList<String>()
        for (l in libraries(vj, dir)) cp.add(File(rootAbs, l.first).absolutePath)
        cp.add(File(rootAbs, "$dir/versions/$ver/$ver.jar").absolutePath)
        val natives = File(gameDir, "versions/$ver/natives").apply { mkdirs() }
        val ph = mapOf(
            "auth_player_name" to user, "version_name" to ver, "game_directory" to gameDir.absolutePath,
            "assets_root" to File(gameDir, "assets").absolutePath, "assets_index_name" to vj.getJSONObject("assetIndex").getString("id"),
            "auth_uuid" to UUID.nameUUIDFromBytes("OfflinePlayer:$user".toByteArray()).toString().replace("-", ""),
            "auth_access_token" to "0", "user_type" to "legacy", "version_type" to "release",
            "natives_directory" to natives.absolutePath, "launcher_name" to "MCNative", "launcher_version" to "1.0.0",
            "classpath" to cp.joinToString(":"), "resolution_width" to "$w", "resolution_height" to "$h",
            "clientid" to "0", "auth_xuid" to "0", "user_properties" to "{}", "game_assets" to File(gameDir, "assets").absolutePath
        )
        fun sub(s: String): String { var r = s; ph.forEach { (k, v) -> r = r.replace("\${$k}", v) }; return r }
        fun collect(arr: JSONArray?): List<String> {
            val out = ArrayList<String>(); if (arr == null) return out
            for (i in 0 until arr.length()) {
                val a = arr.get(i)
                if (a is String) out.add(sub(a))
                else if (a is JSONObject && rulesOk(a.optJSONArray("rules"))) {
                    val v = a.get("value")
                    if (v is JSONArray) for (j in 0 until v.length()) out.add(sub(v.getString(j))) else out.add(sub(v.toString()))
                }
            }
            return out
        }
        val jvm = ArrayList<String>(); val game = ArrayList<String>()
        val args = vj.optJSONObject("arguments")
        if (args != null) { jvm.addAll(collect(args.optJSONArray("jvm"))); game.addAll(collect(args.optJSONArray("game"))) }
        else {
            jvm.addAll(listOf("-Djava.library.path=${natives.absolutePath}", "-cp", cp.joinToString(":")))
            game.addAll(vj.getString("minecraftArguments").split(" ").map { sub(it) })
        }
        game.addAll(listOf("--lang", lang))
        val cmd = ArrayList<String>()
        cmd.add(java.absolutePath); cmd.add("-Xmx${ram}M"); cmd.add("-Xms${minOf(ram, 512)}M")
        cmd.addAll(jvm); cmd.add(vj.getString("mainClass")); cmd.addAll(game)

        val log = File(gameDir, "logs/mcnative-launch.log").apply { parentFile?.mkdirs() }
        val pb = ProcessBuilder(cmd).directory(gameDir).redirectErrorStream(true).redirectOutput(log)
        pb.environment()["JAVA_HOME"] = javaHome(21).absolutePath
        pb.environment()["POJAV_RENDERER"] = renderer
        pb.environment()["HOME"] = gameDir.absolutePath
        val p = try { pb.start() } catch (e: IOException) {
            throw IllegalStateException("Falha ao executar o Java: ${e.message}")
        }
        Thread.sleep(3000)
        val alive = try { p.exitValue(); false } catch (e: IllegalThreadStateException) { true }
        if (!alive) {
            val tail = log.takeIf { it.isFile }?.readLines()?.takeLast(8)?.joinToString("\n") ?: ""
            throw IllegalStateException("O processo do jogo encerrou (código ${p.exitValue()}).\n$tail")
        }
        JSObject().apply { put("ok", true); put("log", log.absolutePath) }
    }
}
