package com.levilab

import android.content.Context
import android.os.Build
import dalvik.system.DexFile
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

class PkgInfo(
    val pkg: String,
    val version: String,
    val sourceDir: String,
    val splits: List<String>,
    val nativeDir: String?
)

class Prep(val dir: File?, val message: String)

@Suppress("DEPRECATION")
object Engine {
    val PACKAGES = listOf("com.mojang.minecraftpe.preview", "com.mojang.minecraftpe")
    const val MAIN_LIB = "libminecraftpe.so"
    private const val CODE_FLAGS = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY

    fun detect(ctx: Context): PkgInfo? {
        var first: PkgInfo? = null
        for (p in PACKAGES) {
            try {
                val pi = ctx.packageManager.getPackageInfo(p, 0)
                val ai = pi.applicationInfo
                if (ai == null) { AppLog.w("DETECT $p: applicationInfo is null"); continue }
                val info = PkgInfo(p, "${pi.versionName} (code ${pi.longVersionCode})", ai.sourceDir,
                    ai.splitSourceDirs?.toList() ?: emptyList(), ai.nativeLibraryDir)
                AppLog.w("DETECT found $p version=${info.version} source=${info.sourceDir} splits=${info.splits.size} nativeDir=${info.nativeDir}")
                if (first == null) first = info
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                AppLog.w("DETECT $p: not installed or not visible")
            } catch (t: Throwable) {
                AppLog.w("DETECT $p: error $t")
            }
        }
        if (first != null && first.pkg.endsWith(".preview") && !first.version.startsWith("1.26.70.24"))
            AppLog.w("NOTE: Preview version is ${first.version}, not 1.26.70.24")
        return first
    }

    fun prepare(ctx: Context, info: PkgInfo): Prep {
        AppLog.w("PREPARE start for ${info.pkg}; ABIs=${Build.SUPPORTED_ABIS.joinToString()}")
        val nd = info.nativeDir
        if (nd != null) {
            val m = File(nd, MAIN_LIB)
            val v = ElfUtil.validate(m)
            AppLog.w("PREPARE nativeLibraryDir ${m.path}: ${v.reason}")
            if (v.ok) return Prep(File(nd), "Using nativeLibraryDir directly. $MAIN_LIB: ${v.reason}")
        }
        val problems = ArrayList<String>()
        for (abi in Build.SUPPORTED_ABIS) {
            val out = File(ctx.filesDir, "native/${info.pkg}/$abi")
            out.mkdirs()
            var foundMain = false
            for (apk in listOf(info.sourceDir) + info.splits) {
                try {
                    ZipFile(apk).use { zf ->
                        val en = zf.entries()
                        while (en.hasMoreElements()) {
                            val e = en.nextElement()
                            if (e.isDirectory || !e.name.startsWith("lib/$abi/") || !e.name.endsWith(".so")) continue
                            val name = e.name.substringAfterLast('/')
                            val dest = File(out, name)
                            if (dest.isFile && dest.length() == e.size) { if (name == MAIN_LIB) foundMain = true; continue }
                            val tmp = File(out, "$name.tmp")
                            zf.getInputStream(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                            if (tmp.length() != e.size) { tmp.delete(); throw java.io.IOException("size mismatch for $name: ${tmp.length()} vs ${e.size}") }
                            if (!tmp.renameTo(dest)) throw java.io.IOException("rename failed for $name")
                            AppLog.w("EXTRACT ${e.name} from ${File(apk).name} -> ${dest.length()} bytes")
                            if (name == MAIN_LIB) foundMain = true
                        }
                    }
                } catch (t: Throwable) {
                    val s = "$abi: $apk: $t"
                    problems.add(s); AppLog.w("EXTRACT error $s")
                }
            }
            if (foundMain) {
                val v = ElfUtil.validate(File(out, MAIN_LIB))
                AppLog.w("PREPARE verify $abi $MAIN_LIB: ${v.reason}")
                return if (v.ok) Prep(out, "Extracted to ${out.path}. $MAIN_LIB: ${v.reason}")
                else Prep(null, "Extracted $MAIN_LIB for $abi but it is invalid: ${v.reason}")
            }
        }
        return Prep(null, "Could not obtain a valid $MAIN_LIB. " + (if (problems.isEmpty()) "It was not found in the base APK or the splits for any supported ABI." else problems.joinToString("; ")))
    }

    fun launch(ctx: Context, info: PkgInfo): String {
        val i = ctx.packageManager.getLaunchIntentForPackage(info.pkg)
        if (i == null) { AppLog.w("LAUNCH no launch intent for ${info.pkg}"); return "No launch intent for ${info.pkg}" }
        return try {
            ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.w("LAUNCH startActivity called for ${info.pkg} (this only shows the request was sent)")
            "startActivity was called for ${info.pkg}"
        } catch (t: Throwable) {
            AppLog.w("LAUNCH failed: $t"); "Launch failed: $t"
        }
    }

    fun experimentalLoad(ctx: Context, info: PkgInfo, dir: File?): String {
        AppLog.w("EXP begin (experimental, a native crash may kill this process)")
        if (dir == null) return fail("no verified library directory. Run prepare first.")
        val pctx = try { ctx.createPackageContext(info.pkg, CODE_FLAGS) } catch (t: Throwable) { return fail("createPackageContext: $t") }
        AppLog.w("EXP createPackageContext ok, classLoader=${pctx.classLoader}")
        val main = File(dir, MAIN_LIB)
        val v = ElfUtil.validate(main)
        if (!v.ok) return fail("$MAIN_LIB invalid: ${v.reason}")
        val elf = try { ElfUtil.parse(main) } catch (t: Throwable) { return fail("ELF parse: $t") }
        AppLog.w("EXP NEEDED=${elf.needed}")
        for (n in elf.needed) {
            val f = File(dir, n)
            if (!f.isFile) { AppLog.w("EXP $n not in game libs, assumed system library, not loaded by us"); continue }
            val vv = ElfUtil.validate(f, requireSize = false)
            if (!vv.ok) return fail("dependency $n invalid: ${vv.reason}")
            AppLog.w("EXP System.load $n ...")
            try { System.load(f.absolutePath) } catch (t: Throwable) { return fail("System.load($n): $t") }
            AppLog.w("EXP loaded $n")
        }
        AppLog.w("EXP System.load $MAIN_LIB ...")
        try { System.load(main.absolutePath) } catch (t: Throwable) { return fail("System.load($MAIN_LIB): $t") }
        AppLog.w("EXP System.load returned normally")
        return "System.load returned without an exception. That is all this proves: it does NOT mean the game runs in this process."
    }

    private fun fail(why: String): String { AppLog.w("EXP FAILED: $why"); return "FAILED: $why" }

    fun probe(ctx: Context, info: PkgInfo, dir: File?): List<String> {
        val out = ArrayList<String>()
        fun o(s: String) { out.add(s); AppLog.w("PROBE $s") }
        val cl = try { ctx.createPackageContext(info.pkg, CODE_FLAGS).classLoader } catch (t: Throwable) { o("createPackageContext failed: $t"); null }
        o("== classes under com.mojang ==")
        val names = sortedSetOf<String>()
        for (apk in listOf(info.sourceDir) + info.splits) {
            try {
                val df = DexFile(apk)
                val e = df.entries()
                while (e.hasMoreElements()) { val n = e.nextElement(); if (n.startsWith("com.mojang")) names.add(n) }
                df.close()
            } catch (t: Throwable) { o("DexFile(${File(apk).name}) failed: $t") }
        }
        o("count=${names.size}")
        names.take(300).forEach { o(it) }
        if (cl != null) {
            o("== MainActivity hierarchy ==")
            try {
                var c: Class<*>? = Class.forName("com.mojang.minecraftpe.MainActivity", false, cl)
                while (c != null) { o(c.name); c = c.superclass }
            } catch (t: Throwable) { o("failed: $t") }
            o("== native methods ==")
            var shown = 0
            for (n in names.take(300)) {
                try {
                    val c = Class.forName(n, false, cl)
                    for (m in c.declaredMethods) if (Modifier.isNative(m.modifiers) && shown < 400) { o("$n.${m.name}"); shown++ }
                } catch (t: Throwable) { o("$n: ${t.javaClass.simpleName}") }
            }
            o("native methods listed=$shown")
        }
        o("== ELF ==")
        val main = dir?.let { File(it, MAIN_LIB) }
        if (main == null) o("no verified library directory, run prepare first")
        else try {
            val elf = ElfUtil.parse(main)
            o("SONAME=${elf.soname}")
            o("NEEDED (${elf.needed.size}): ${elf.needed.joinToString()}")
            o("JNI names in dynstr: ${elf.jni.size}")
            elf.jni.take(400).forEach { o(it) }
        } catch (t: Throwable) { o("ELF parse failed: $t") }
        return out
    }
}
